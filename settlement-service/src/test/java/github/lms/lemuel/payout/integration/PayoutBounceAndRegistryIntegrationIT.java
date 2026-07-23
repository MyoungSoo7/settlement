package github.lms.lemuel.payout.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.payout.application.port.in.RecordPayoutBounceUseCase;
import github.lms.lemuel.payout.application.port.in.RegisterSellerBankAccountUseCase;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed D1 — 셀러 계좌 레지스트리 + 송금 반송 재지급 E2E (실 PostgreSQL + 실 Flyway
 * V20260723100000/100100 검증).
 *
 * <p>검증(AC):
 * <ol>
 *   <li>COMPLETED 송금 반송 → 사유 기록 + 정정계좌(레지스트리) 로 신규 REQUESTED payout 재발행,
 *       원 COMPLETED payout 불변.</li>
 *   <li>같은 반송 재호출 → 재지급 재생성 없음(멱등), payout·bounce 각 1건.</li>
 *   <li>동시 이중 반송(2스레드) → payout_id UNIQUE 경합으로 재지급 정확히 1건.</li>
 *   <li>레지스트리 등록계좌 우선, 미등록 시 플레이스홀더 폴백.</li>
 * </ol>
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
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
class PayoutBounceAndRegistryIntegrationIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_bounce_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("POSTGRES_USER", POSTGRES::getUsername);
        registry.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired RecordPayoutBounceUseCase bounceUseCase;
    @Autowired RegisterSellerBankAccountUseCase registerUseCase;
    @Autowired RequestPayoutUseCase requestPayoutUseCase;
    @Autowired LoadPayoutPort loadPayoutPort;
    @Autowired SavePayoutPort savePayoutPort;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("반송 → 정정계좌 재지급 (원 COMPLETED 불변) + 재호출 멱등")
    void bounceReissuesWithCorrectedAccount() {
        long sellerId = 7101L;
        long originalId = seedCompletedPayout(6101L, sellerId, "95500.00");

        // 계좌 정정 — 원 payout 은 placeholder(KB) 로 생성됐고, 반송 전 정정 계좌를 레지스트리에 등록한다.
        registerUseCase.register(sellerId, "SHINHAN", "222-22-222222", "홍길동");

        RecordPayoutBounceUseCase.BounceOutcome outcome =
                bounceUseCase.recordBounce(originalId, "ACCOUNT_CLOSED", "op-1");

        Long reissuedId = outcome.reissuedPayout().getId();
        assertThat(reissuedId).isNotNull();

        // 원 COMPLETED payout 불변 (상태·계좌 유지)
        assertThat(jdbc.queryForMap("SELECT status, bank_code, settlement_id FROM payouts WHERE id = ?", originalId))
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("COMPLETED");
                    assertThat(row.get("bank_code")).isEqualTo("KB");
                    assertThat(row.get("settlement_id")).isNotNull();
                });
        // 재발행 payout — REQUESTED, settlement_id NULL(이중지급 가드 보존), 정정계좌(SHINHAN), 원 금액 승계
        assertThat(jdbc.queryForMap("SELECT status, bank_code, settlement_id, amount FROM payouts WHERE id = ?",
                reissuedId))
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("REQUESTED");
                    assertThat(row.get("bank_code")).isEqualTo("SHINHAN");
                    assertThat(row.get("settlement_id")).isNull();
                    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("95500.00");
                });
        // 반송 레코드 — resolved 링크
        assertThat(jdbc.queryForMap("SELECT payout_id, resolved_payout_id, reason FROM payout_bounces "
                + "WHERE payout_id = ?", originalId))
                .satisfies(row -> {
                    assertThat(row.get("resolved_payout_id")).isEqualTo(reissuedId);
                    assertThat(row.get("reason")).isEqualTo("ACCOUNT_CLOSED");
                });

        // 재호출 — 멱등: 재지급 재생성 없음, 같은 reissued 반환
        RecordPayoutBounceUseCase.BounceOutcome replay =
                bounceUseCase.recordBounce(originalId, "ACCOUNT_CLOSED", "op-1");
        assertThat(replay.reissuedPayout().getId()).isEqualTo(reissuedId);
        assertThat(countBounces(originalId)).isEqualTo(1);
        assertThat(countReissued(sellerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 이중 반송(2스레드) → payout_id UNIQUE 경합, 재지급 정확히 1건")
    void concurrentDoubleBounceReissuesOnce() throws InterruptedException {
        long sellerId = 7301L;
        long originalId = seedCompletedPayout(6301L, sellerId, "50000.00");
        registerUseCase.register(sellerId, "WOORI", "333-33-333333", "김철수");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<String> outcomes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    bounceUseCase.recordBounce(originalId, "ACCOUNT_CLOSED", "op");
                    outcomes.add("OK");
                } catch (RuntimeException e) {
                    outcomes.add("CONFLICT:" + e.getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 반송 1건, 재지급 정확히 1건 — 이중지급 없음
        assertThat(countBounces(originalId)).isEqualTo(1);
        assertThat(countReissued(sellerId)).isEqualTo(1);
        // 원 payout 불변
        assertThat(jdbc.queryForObject("SELECT status FROM payouts WHERE id = ?", String.class, originalId))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("레지스트리 등록계좌 우선, 미등록 시 플레이스홀더 폴백")
    void registryPriorityWithPlaceholderFallback() {
        // 등록 셀러 — 등록 계좌(WOORI) 로 payout 생성
        long registered = 7201L;
        registerUseCase.register(registered, "WOORI", "444-44-444444", "이영희");
        long regSettlement = seedSettlement(6201L);
        Payout regPayout = requestPayoutUseCase.requestPayoutOfType(
                regSettlement, registered, new BigDecimal("10000.00"), PayoutType.IMMEDIATE).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT bank_code FROM payouts WHERE id = ?", String.class, regPayout.getId()))
                .isEqualTo("WOORI");

        // 미등록 셀러 — 플레이스홀더(KB, 000-...) 폴백
        long unregistered = 7202L;
        long unregSettlement = seedSettlement(6202L);
        Payout unregPayout = requestPayoutUseCase.requestPayoutOfType(
                unregSettlement, unregistered, new BigDecimal("10000.00"), PayoutType.IMMEDIATE).orElseThrow();
        assertThat(jdbc.queryForMap("SELECT bank_code FROM payouts WHERE id = ?", unregPayout.getId()))
                .satisfies(row -> assertThat(row.get("bank_code")).isEqualTo("KB"));
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    /** DONE 정산 + 그 정산의 즉시지급 Payout 을 placeholder 계좌로 생성 후 COMPLETED 로 승격. */
    private long seedCompletedPayout(long paymentId, long sellerId, String amount) {
        long settlementId = seedSettlement(paymentId);
        Payout requested = requestPayoutUseCase.requestPayoutOfType(
                settlementId, sellerId, new BigDecimal(amount), PayoutType.IMMEDIATE).orElseThrow();
        // 실 은행 송금 완료를 도메인 상태머신으로 모사: REQUESTED→SENDING→COMPLETED.
        // (raw UPDATE 대신 markCompleted + SavePayoutPort — 지급 레코드 불변 규율/guard 준수)
        Payout p = loadPayoutPort.findById(requested.getId()).orElseThrow();
        p.startSending();
        p.markCompleted("FB-SEED");
        savePayoutPort.save(p);
        return requested.getId();
    }

    private long seedSettlement(long paymentId) {
        return tx.execute(s -> settlementRepo.save(newSettlement(
                paymentId, paymentId + 3000L, "100000.00", "3500.00", "96500.00", "DONE")).getId());
    }

    private SettlementJpaEntity newSettlement(long paymentId, long orderId, String payment,
                                              String commission, String net, String status) {
        SettlementJpaEntity e = new SettlementJpaEntity();
        e.setPaymentId(paymentId);
        e.setOrderId(orderId);
        e.setPaymentAmount(new BigDecimal(payment));
        e.setCommission(new BigDecimal(commission));
        e.setNetAmount(new BigDecimal(net));
        e.setStatus(status);
        e.setSettlementDate(LocalDate.now());
        return e;
    }

    private int countBounces(long payoutId) {
        return jdbc.queryForObject("SELECT count(*) FROM payout_bounces WHERE payout_id = ?",
                Integer.class, payoutId);
    }

    /** 재발행 payout 은 settlement_id NULL 인 해당 셀러의 REQUESTED 송금. */
    private int countReissued(long sellerId) {
        return jdbc.queryForObject("SELECT count(*) FROM payouts WHERE seller_id = ? AND settlement_id IS NULL",
                Integer.class, sellerId);
    }
}
