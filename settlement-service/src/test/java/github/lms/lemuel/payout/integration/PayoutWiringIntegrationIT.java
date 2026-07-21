package github.lms.lemuel.payout.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.service.PayoutConcurrentClaimException;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.adapter.in.batch.confirm.SettlementConfirmItemWriter;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.batch.infrastructure.item.Chunk;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seed-p0-1 — 정산 확정·홀드백 해제 시 지급유형별 Payout 자동 생성 배선을 실 PostgreSQL 로 검증한다.
 *
 * <p>Flyway V20260721120000(payout_type + (settlement_id, payout_type) 부분 유니크)이 적용된 실 스키마로
 * 부팅한다(ddl-auto=validate). 검증 대상(시드 AC):
 * <ol>
 *   <li>AC-1: 확정 경로({@link SettlementConfirmItemWriter})에서 IMMEDIATE Payout(미해제 holdback 제외)이 생성.</li>
 *   <li>AC-2: 홀드백 해제 경로({@link ReleaseHoldbackUseCase})에서 HOLDBACK_RELEASE Payout(잔여 보류액)이 생성.</li>
 *   <li>AC-3: 중복 처리·동시 2스레드에도 (정산, 유형)당 Payout 이 정확히 1건.</li>
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
class PayoutWiringIntegrationIT {

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

    @Autowired SettlementConfirmItemWriter confirmWriter;
    @Autowired ReleaseHoldbackUseCase releaseUseCase;
    @Autowired RequestPayoutUseCase requestPayoutUseCase;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM public.payouts");
        jdbc.update("DELETE FROM public.settlement_payment_view");
        // 확정 플로우가 적재한 원장 아웃박스가 settlements 를 FK 참조 — 부모보다 먼저 지운다.
        jdbc.update("DELETE FROM public.ledger_outbox");
        jdbc.update("DELETE FROM public.settlements");
    }

    /** 판매자 해석용 프로젝션 시드 — LoadSellerIdPort 가 settlement_payment_view.seller_id 를 읽는다. */
    private void seedPaymentView(long paymentId, long sellerId) {
        jdbc.update("INSERT INTO public.settlement_payment_view " +
                        "(payment_id, order_id, amount, status, seller_id, updated_at) " +
                        "VALUES (?, ?, 10000.00, 'CAPTURED', ?, now())",
                paymentId, paymentId + 1, sellerId);
    }

    private long settlementIdByPayment(long paymentId) {
        return jdbc.queryForObject(
                "SELECT id FROM public.settlements WHERE payment_id = ?", Long.class, paymentId);
    }

    private int payoutCount(long settlementId, PayoutType type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.payouts WHERE settlement_id = ? AND payout_type = ?",
                Integer.class, settlementId, type.name());
    }

    private BigDecimal payoutAmount(long settlementId, PayoutType type) {
        return jdbc.queryForObject(
                "SELECT amount FROM public.payouts WHERE settlement_id = ? AND payout_type = ?",
                BigDecimal.class, settlementId, type.name());
    }

    // ── AC-1: 확정 경로 → IMMEDIATE Payout ────────────────────────────────────────────────
    @Test
    @DisplayName("AC-1: 정산 확정 시 IMMEDIATE Payout 이 즉시지급액(net−미해제 holdback)으로 자동 생성된다")
    void confirm_createsImmediatePayout() {
        long paymentId = 5001L;
        long sellerId = 42L;
        seedPaymentView(paymentId, sellerId);

        // 결제 10,000 / 수수료 3% 300 / net 9,700 / holdback 30% 2,910 → 즉시지급 6,790
        Settlement s = Settlement.createFromPayment(paymentId, paymentId + 1, new BigDecimal("10000"), LocalDate.now());
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.now().plusDays(30));
        s.confirm();
        BigDecimal expectedImmediate = s.getImmediatePayoutAmount();

        tx.executeWithoutResult(t -> confirmWriter.write(new Chunk<>(List.of(s))));
        long settlementId = settlementIdByPayment(paymentId);

        assertThat(payoutCount(settlementId, PayoutType.IMMEDIATE)).isEqualTo(1);
        assertThat(payoutAmount(settlementId, PayoutType.IMMEDIATE)).isEqualByComparingTo(expectedImmediate);
        // 홀드백 해제 지급은 아직 없다.
        assertThat(payoutCount(settlementId, PayoutType.HOLDBACK_RELEASE)).isZero();
    }

    @Test
    @DisplayName("AC-3(중복): 확정 후 즉시지급 요청이 재전달돼도 IMMEDIATE Payout 은 1건")
    void confirm_isIdempotentAcrossReDelivery() {
        long paymentId = 5002L;
        long sellerId = 43L;
        seedPaymentView(paymentId, sellerId);
        Settlement s = Settlement.createFromPayment(paymentId, paymentId + 1, new BigDecimal("10000"), LocalDate.now());
        s.confirm();

        tx.executeWithoutResult(t -> confirmWriter.write(new Chunk<>(List.of(s))));
        long settlementId = settlementIdByPayment(paymentId);

        // 이벤트/요청 재전달 재현 — 같은 (정산, IMMEDIATE) 을 다시 요청해도 멱등 반환(재생성 없음).
        BigDecimal immediate = payoutAmount(settlementId, PayoutType.IMMEDIATE);
        requestPayoutUseCase.requestPayoutOfType(settlementId, sellerId, immediate, PayoutType.IMMEDIATE);

        assertThat(payoutCount(settlementId, PayoutType.IMMEDIATE)).isEqualTo(1);
    }

    // ── AC-2: 홀드백 해제 경로 → HOLDBACK_RELEASE Payout ───────────────────────────────────
    @Test
    @DisplayName("AC-2: 홀드백 해제 시 HOLDBACK_RELEASE Payout 이 잔여 보류액으로 자동 생성된다")
    void holdbackRelease_createsHoldbackReleasePayout() {
        long paymentId = 6001L;
        long sellerId = 77L;
        seedPaymentView(paymentId, sellerId);
        LocalDate today = LocalDate.now();

        // release_date=어제(만기), 잔여 보류액 1,000 인 정산.
        Long settlementId = tx.execute(t -> {
            SettlementJpaEntity e = newSettlement(paymentId, paymentId + 1, "10000.00", "300.00", "9700.00");
            e.setHoldbackAmount(new BigDecimal("1000.00"));
            e.setHoldbackRate(new BigDecimal("0.1000"));
            e.setHoldbackReleaseDate(today.minusDays(1));
            e.setHoldbackReleased(false);
            return settlementRepo.save(e).getId();
        });

        int released = releaseUseCase.releaseAllDueOn(today);

        assertThat(released).isEqualTo(1);
        assertThat(payoutCount(settlementId, PayoutType.HOLDBACK_RELEASE)).isEqualTo(1);
        assertThat(payoutAmount(settlementId, PayoutType.HOLDBACK_RELEASE)).isEqualByComparingTo("1000.00");
        // 즉시지급은 이 경로에서 만들지 않는다.
        assertThat(payoutCount(settlementId, PayoutType.IMMEDIATE)).isZero();
    }

    // ── AC-3: 동시 2스레드에도 (정산, 유형)당 1건 ─────────────────────────────────────────
    @Test
    @DisplayName("AC-3(동시성): 동일 (정산,유형) 을 2스레드가 동시에 요청해도 Payout 은 정확히 1건")
    void concurrentRequests_yieldExactlyOnePayout() throws Exception {
        long settlementId = 7100L;
        long sellerId = 88L;
        // FK(fk_payouts_settlement) 충족 — 실존 부모 정산을 심는다.
        tx.executeWithoutResult(t -> jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   version, created_at, updated_at)
                VALUES (?, ?, ?, 10000.00, 0.00, 300.00, 0.0300, 9700.00, 0.00, 0.0000, false,
                        CURRENT_DATE, 'DONE', 0, now(), now())
                """, settlementId, settlementId, settlementId + 1));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Void>> futures = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    requestPayoutUseCase.requestPayoutOfType(
                            settlementId, sellerId, new BigDecimal("6790"), PayoutType.IMMEDIATE);
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                try { f.get(); }
                catch (Exception e) { synchronized (errors) { errors.add(e.getCause() != null ? e.getCause() : e); } }
            }
        } finally {
            pool.shutdownNow();
        }

        // 하드 백스톱: 유형별 정확히 1건.
        assertThat(payoutCount(settlementId, PayoutType.IMMEDIATE)).isEqualTo(1);
        // 진 쪽이 있었다면 실패가 아니라 정상 경합(PayoutConcurrentClaimException)으로만 표면화된다.
        assertThat(errors).allSatisfy(e ->
                assertThat(e).isInstanceOf(PayoutConcurrentClaimException.class));
    }

    private SettlementJpaEntity newSettlement(long paymentId, long orderId,
                                              String paymentAmount, String commission, String netAmount) {
        SettlementJpaEntity s = new SettlementJpaEntity();
        s.setPaymentId(paymentId);
        s.setOrderId(orderId);
        s.setPaymentAmount(new BigDecimal(paymentAmount));
        s.setCommission(new BigDecimal(commission));
        s.setNetAmount(new BigDecimal(netAmount));
        s.setStatus("PROCESSING");
        s.setSettlementDate(LocalDate.now());
        return s;
    }
}
