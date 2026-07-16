package github.lms.lemuel.payout.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payout 2-phase 집행의 <b>재송금 0</b> 보장을 실제 PostgreSQL 로 검증한다.
 *
 * <p>핵심 시나리오: 펌뱅킹 송금이 성공한 뒤 확정(phase3) 직전에 프로세스가 죽으면 행은 SENDING 으로 남는다.
 * 이후 배치({@code executeAllPending})는 REQUESTED 만 조회하므로 그 SENDING 건을 다시 집지 않아 <b>재송금이
 * 원천 차단</b>된다. 이를 "SENDING 상태로 심고 → 배치 재실행 → 펌뱅킹 send 호출 0" 으로 증명한다.
 * 함께 정상 경로(REQUESTED → COMPLETED)와 건별 PAYOUT_EXECUTED 감사 적재도 확인한다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@Import(PayoutExecutionResendGuardIT.Config.class)
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class PayoutExecutionResendGuardIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    /** 펌뱅킹 send 호출 횟수를 세는 성공 더블 — 실제 송금 대신 호출만 계수한다. */
    static final class CountingFirmBanking implements FirmBankingPort {
        final AtomicInteger sendCount = new AtomicInteger(0);

        @Override
        public String send(SellerBankAccount account, BigDecimal amount, String referenceId) {
            return "FB-" + sendCount.incrementAndGet();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary
        CountingFirmBanking countingFirmBanking() {
            return new CountingFirmBanking();
        }
    }

    @Autowired ExecutePayoutUseCase executeUseCase;
    @Autowired CountingFirmBanking firmBanking;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        firmBanking.sendCount.set(0);
        jdbc.update("DELETE FROM public.payouts");
        jdbc.update("DELETE FROM public.settlements");
    }

    /** FK(fk_payouts_settlement) 충족을 위해 부모 정산 + payout 을 심고 payout id 를 돌려준다. */
    private long seedPayout(long settlementId, String status) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   version, created_at, updated_at)
                VALUES (?, ?, ?, 10000.00, 0.00, 300.00, 0.0300, 9700.00, 0.00, 0.0000, false,
                        CURRENT_DATE, 'REQUESTED', 0, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, settlementId, settlementId, settlementId + 1);
        String sentAt = "SENDING".equals(status) ? "now()" : "null";
        jdbc.update("INSERT INTO public.payouts " +
                        "(settlement_id, seller_id, amount, bank_code, bank_account_number, account_holder_name, " +
                        " status, retry_count, version, requested_at, sent_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, now(), " + sentAt + ", now(), now())",
                settlementId, 1L, new BigDecimal("50000"), "KB", "123-45-678901", "홍길동", status);
        return jdbc.queryForObject(
                "SELECT id FROM public.payouts WHERE settlement_id = ?", Long.class, settlementId);
    }

    private String statusOf(long payoutId) {
        return jdbc.queryForObject("SELECT status FROM public.payouts WHERE id = ?", String.class, payoutId);
    }

    /** PAYOUT_EXECUTED 감사 건수(payout 단위). outcome 세부는 단위 테스트가 검증하므로 여기선 존재만 확인한다. */
    private long auditCount(long payoutId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.audit_logs WHERE action = 'PAYOUT_EXECUTED' AND resource_id = ?",
                Long.class, String.valueOf(payoutId));
    }

    @Test
    @DisplayName("정상 경로: REQUESTED → 펌뱅킹 1회 송금 → COMPLETED, PAYOUT_EXECUTED(COMPLETED) 감사 1건")
    void requestedPayout_isSentOnceAndCompleted() {
        long id = seedPayout(7001L, "REQUESTED");

        ExecutePayoutUseCase.ExecutionReport report = executeUseCase.executeAllPending();

        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(firmBanking.sendCount.get()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(auditCount(id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("재송금 0: SENDING(송금 후 크래시 상태) 건은 배치가 다시 집지 않아 펌뱅킹 send 가 한 번도 호출되지 않는다")
    void sendingPayout_isNeverResentByBatch() {
        // 펌뱅킹 송금은 성공했으나 확정 tx 커밋 전에 죽은 상태를 재현: 행을 SENDING 으로 심는다.
        long stuck = seedPayout(7002L, "SENDING");

        // 배치를 두 번 돌려도 REQUESTED 가 없으므로 send 는 0, 상태는 SENDING 그대로(자동 재송금 없음).
        executeUseCase.executeAllPending();
        executeUseCase.executeAllPending();

        assertThat(firmBanking.sendCount.get()).isZero();
        assertThat(statusOf(stuck)).isEqualTo("SENDING");
        // SENDING 잔류는 integrity stuck 감시가 별도로 잡아 운영자 수동 조치로 넘긴다(이 IT 범위 밖).
    }

    @Test
    @DisplayName("혼재: REQUESTED 는 송금·완료되고 SENDING 크래시 건은 건드리지 않는다(send 정확히 1회)")
    void mixedBatch_onlyRequestedIsSent() {
        long fresh = seedPayout(7003L, "REQUESTED");
        long stuck = seedPayout(7004L, "SENDING");

        executeUseCase.executeAllPending();

        assertThat(firmBanking.sendCount.get()).isEqualTo(1);
        assertThat(statusOf(fresh)).isEqualTo("COMPLETED");
        assertThat(statusOf(stuck)).isEqualTo("SENDING");
    }
}
