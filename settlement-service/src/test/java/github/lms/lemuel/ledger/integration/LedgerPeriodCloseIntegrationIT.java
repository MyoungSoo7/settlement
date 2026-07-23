package github.lms.lemuel.ledger.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.ledger.application.port.in.CloseLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.CreateLedgerEntryUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerTrialBalanceUseCase;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import github.lms.lemuel.ledger.domain.LedgerTrialBalance;
import github.lms.lemuel.ledger.domain.exception.LedgerPeriodClosedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A1 Phase 5 — 원장 월마감·기간잠금·기간별 확정 시산표를 실 PostgreSQL(Flyway validate)로 검증한다.
 *
 * <p>검증 항목:
 * <ol>
 *   <li>기간 확정 시산표 집계 정확성 + 마감 시 CLOSED 전이 + 합계 스냅샷.</li>
 *   <li>마감된 기간을 대상으로 한 신규 원분개 거부(도메인 예외).</li>
 *   <li>마감 기간 대상 역분개는 재개봉 없이 다음 OPEN 기간(1일)으로 재지정 전기.</li>
 * </ol>
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
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class LedgerPeriodCloseIntegrationIT {

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

    @Autowired CreateLedgerEntryUseCase createLedger;
    @Autowired ReverseEntryUseCase reverseEntry;
    @Autowired CloseLedgerPeriodUseCase closePeriod;
    @Autowired GetLedgerTrialBalanceUseCase trialBalance;
    @Autowired GetLedgerPeriodUseCase getPeriod;
    @Autowired JdbcTemplate jdbc;

    private static final YearMonth JAN = YearMonth.of(2026, 1);

    @BeforeEach
    void reset() {
        // POSTED 원장·DONE 정산 불변성 트리거가 DELETE 를 막으므로 TRUNCATE(트리거 우회)로 정리한다.
        jdbc.execute("TRUNCATE TABLE public.ledger_periods, public.ledger_outbox, public.ledger_entries, "
                + "public.settlement_adjustments, public.chargebacks, "
                + "public.pg_reconciliation_runs, public.pg_reconciliation_discrepancies, "
                + "public.settlements RESTART IDENTITY CASCADE");
    }

    private void seedSettlement(long id, String payment, String commission, String net,
                                LocalDate settlementDate, String status) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0.00, ?, 0.0300, ?, 0.00, 0.0000, false, ?, ?,
                        now(), 0, now(), now())
                """, id, id, id + 1, new BigDecimal(payment), new BigDecimal(commission),
                new BigDecimal(net), settlementDate, status);
    }

    @Test
    @DisplayName("확정 시산표 집계 정확 + 마감 시 CLOSED 전이 및 합계 스냅샷")
    void trialBalanceAggregation_andClose_snapshotsTotals() {
        seedSettlement(7001L, "10000", "300", "9700", LocalDate.of(2026, 1, 15), "DONE");
        createLedger.createFromSettlement(7001L);

        LedgerTrialBalance tb = trialBalance.getForPeriod(JAN);
        assertThat(tb.isBalanced()).isTrue();
        assertThat(tb.getTotalDebit()).isEqualByComparingTo("10000.00");
        assertThat(tb.getTotalCredit()).isEqualByComparingTo("10000.00");

        LedgerPeriod closed = closePeriod.close(JAN, "admin");
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getTotalDebit()).isEqualByComparingTo("10000.00");
        assertThat(closed.getTotalCredit()).isEqualByComparingTo("10000.00");
        assertThat(getPeriod.getStatus(JAN).isClosed()).isTrue();
    }

    @Test
    @DisplayName("마감된 기간 대상 신규 원분개는 거부되고 원장에 남지 않는다")
    void newEntryIntoClosedPeriod_isRejected() {
        // 활동 없는 1월을 먼저 마감(0=0 균형).
        closePeriod.close(JAN, "admin");

        seedSettlement(7002L, "10000", "300", "9700", LocalDate.of(2026, 1, 20), "DONE");

        assertThatThrownBy(() -> createLedger.createFromSettlement(7002L))
                .isInstanceOf(LedgerPeriodClosedException.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.ledger_entries WHERE reference_id = ? AND reference_type = 'SETTLEMENT'",
                Integer.class, 7002L);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("마감 기간 대상 역분개는 다음 OPEN 기간(2월 1일)으로 재지정 전기된다")
    void reverseIntoClosedPeriod_isRetargetedToNextOpenPeriod() {
        seedSettlement(7003L, "10000", "300", "9700", LocalDate.of(2026, 1, 15), "DONE");
        closePeriod.close(JAN, "admin");

        // 1월(마감) 일자로 환불 역분개 요청 → 2월 1일로 재지정.
        reverseEntry.reverseForRefund(7003L, 8801L, new BigDecimal("10000"), LocalDate.of(2026, 1, 20));

        List<LocalDate> dates = jdbc.queryForList(
                "SELECT DISTINCT settlement_date FROM public.ledger_entries "
                        + "WHERE reference_id = ? AND reference_type = 'REFUND'",
                LocalDate.class, 8801L);

        assertThat(dates).containsExactly(LocalDate.of(2026, 2, 1));
    }
}
