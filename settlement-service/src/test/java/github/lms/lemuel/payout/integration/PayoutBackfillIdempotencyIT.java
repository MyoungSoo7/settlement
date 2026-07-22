package github.lms.lemuel.payout.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.payout.application.port.in.BackfillMissingPayoutsUseCase;
import github.lms.lemuel.payout.domain.PayoutBackfillReport;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payout 미생성 백필 멱등성 통합 테스트 (AC: 2회 연속 실행 시 2회차 변경 0건).
 *
 * <p>검증 시나리오:
 * <ol>
 *   <li>DONE 정산 + seller 프로젝션 뷰를 심는다 (Payout 없음).</li>
 *   <li>1회차 백필 실행 → IMMEDIATE Payout 생성, remaining=0 확인.</li>
 *   <li>2회차 백필 실행 → created=0 (이미 존재하므로 쿼리가 빈 결과 반환).</li>
 * </ol>
 *
 * <p>멱등 메커니즘: {@code findDoneWithoutImmediatePayoutPage} 탐지 쿼리가
 * {@code NOT EXISTS (payouts WHERE payout_type='IMMEDIATE' AND status<>'CANCELED')} 로
 * 이미 생성된 건을 제외한다. 2회차에는 해당 쿼리가 빈 페이지를 반환하므로 created=0.
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
class PayoutBackfillIdempotencyIT {

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

    @Autowired BackfillMissingPayoutsUseCase backfillUseCase;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM public.payouts");
        jdbc.update("DELETE FROM public.settlement_payment_view");
        jdbc.update("DELETE FROM public.ledger_outbox");
        jdbc.update("DELETE FROM public.settlements");
    }

    /**
     * 판매자 계좌 해석에 필요한 settlement_payment_view 프로젝션을 심는다.
     * BackfillMissingPayoutsService 는 LEFT JOIN 으로 seller_id 를 해석하며,
     * NULL 이면 FAILED 로 스킵한다.
     */
    private void seedPaymentView(long paymentId, long sellerId) {
        jdbc.update("INSERT INTO public.settlement_payment_view " +
                        "(payment_id, order_id, amount, status, seller_id, updated_at) " +
                        "VALUES (?, ?, 10000.00, 'CAPTURED', ?, now())",
                paymentId, paymentId + 1, sellerId);
    }

    /**
     * DONE 상태·confirmed_at=now() 인 정산을 직접 삽입한다.
     * BackfillMissingPayoutsService 는 confirmed_at 범위로 대상을 필터링한다.
     */
    private long seedDoneSettlement(long paymentId) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, 10000.00, 0.00, 300.00, 0.0300, 9700.00, 0.00, 0.0000, false,
                        CURRENT_DATE, 'DONE', now(), 0, now(), now())
                """, paymentId, paymentId + 1);
        return jdbc.queryForObject(
                "SELECT id FROM public.settlements WHERE payment_id = ?", Long.class, paymentId);
    }

    private int payoutCount(long settlementId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.payouts WHERE settlement_id = ?",
                Integer.class, settlementId);
    }

    // ── 멱등성: IMMEDIATE Payout 백필 ────────────────────────────────────────────

    @Test
    @DisplayName("IMMEDIATE Payout 백필 — 2회 연속 실행 시 2회차 created=0 (멱등 증명)")
    void immediatePayoutBackfill_secondRunCreatesNothing() {
        LocalDate today = LocalDate.now();
        long paymentId = 30001L;
        long sellerId  = 200L;

        seedPaymentView(paymentId, sellerId);
        long settlementId = seedDoneSettlement(paymentId);

        // ── 1회차: 1건 생성 ────────────────────────────────────────────────────
        PayoutBackfillReport firstRun = backfillUseCase.backfill(today, today, null);

        assertThat(firstRun.created())
                .as("1회차: IMMEDIATE Payout 1건 신규 생성")
                .isGreaterThan(0L);
        assertThat(firstRun.remaining())
                .as("1회차 이후 미생성 잔여 0")
                .isZero();
        assertThat(payoutCount(settlementId)).isEqualTo(1);

        // ── 2회차: 쿼리가 이미 존재하는 Payout 을 NOT EXISTS 로 제외 → created=0 ──
        PayoutBackfillReport secondRun = backfillUseCase.backfill(today, today, null);

        assertThat(secondRun.created())
                .as("2회차: 이미 존재하므로 신규 생성 0건 — 멱등 확인")
                .isZero();
        assertThat(secondRun.remaining())
                .as("2회차 이후에도 잔여 0")
                .isZero();
        // DB 상에서도 payout 건수가 여전히 1건임을 확인 (추가 생성 없음)
        assertThat(payoutCount(settlementId))
                .as("두 번 실행 후에도 Payout 은 1건")
                .isEqualTo(1);
    }

    // ── 멱등성: HOLDBACK_RELEASE Payout 백필 ─────────────────────────────────────

    @Test
    @DisplayName("HOLDBACK_RELEASE Payout 백필 — 2회 연속 실행 시 2회차 created=0 (멱등 증명)")
    void holdbackReleasePayoutBackfill_secondRunCreatesNothing() {
        LocalDate today = LocalDate.now();
        long paymentId = 30002L;
        long sellerId  = 201L;

        seedPaymentView(paymentId, sellerId);

        // DONE + holdback_released=true + holdback_amount>0 인 정산 삽입.
        // net_amount(9700) 는 holdback(2910) 을 포함한 실지급 총액 — 도메인 의미론과 동일
        // (즉시지급 = net - holdback, 해제 시 holdback_amount 는 보존된다).
        jdbc.update("""
                INSERT INTO public.settlements
                  (payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, 10000.00, 0.00, 300.00, 0.0300, 9700.00, 2910.00, 0.3000, true,
                        CURRENT_DATE, 'DONE', now(), 0, now(), now())
                """, paymentId, paymentId + 1);
        long settlementId = jdbc.queryForObject(
                "SELECT id FROM public.settlements WHERE payment_id = ?", Long.class, paymentId);

        // ── 1회차 ────────────────────────────────────────────────────────────
        PayoutBackfillReport firstRun = backfillUseCase.backfill(today, today, null);

        // IMMEDIATE(net-holdback=6790) + HOLDBACK_RELEASE(holdback=2910) 두 종류 생성
        assertThat(firstRun.created())
                .as("1회차: 최소 1건 이상 생성")
                .isGreaterThan(0L);
        assertThat(firstRun.remaining()).isZero();

        // 이중지급 회귀 가드: 해제된 정산의 IMMEDIATE 는 net 전액(9700)이 아니라
        // net - holdback 이어야 한다. 두 payout 합계 = net_amount.
        java.math.BigDecimal immediateAmt = jdbc.queryForObject(
                "SELECT amount FROM public.payouts WHERE settlement_id = ? AND payout_type = 'IMMEDIATE'",
                java.math.BigDecimal.class, settlementId);
        java.math.BigDecimal holdbackAmt = jdbc.queryForObject(
                "SELECT amount FROM public.payouts WHERE settlement_id = ? AND payout_type = 'HOLDBACK_RELEASE'",
                java.math.BigDecimal.class, settlementId);
        assertThat(immediateAmt)
                .as("IMMEDIATE 백필 금액 = net(9700) - holdback(2910)")
                .isEqualByComparingTo("6790.00");
        assertThat(holdbackAmt)
                .as("HOLDBACK_RELEASE 백필 금액 = holdback_amount")
                .isEqualByComparingTo("2910.00");
        assertThat(immediateAmt.add(holdbackAmt))
                .as("두 payout 합계는 net_amount 를 넘지 않는다 (이중지급 금지)")
                .isEqualByComparingTo("9700.00");

        long firstRunCreated = firstRun.created();

        // ── 2회차 ────────────────────────────────────────────────────────────
        PayoutBackfillReport secondRun = backfillUseCase.backfill(today, today, null);

        assertThat(secondRun.created())
                .as("2회차: 기존 Payout 이 NOT EXISTS 로 제외돼 생성 0건 — 멱등")
                .isZero();
        assertThat(secondRun.remaining()).isZero();
        assertThat(payoutCount(settlementId))
                .as("두 번 실행 후에도 Payout 은 1회차와 동일 건수")
                .isEqualTo((int) firstRunCreated);
    }

    // ── 멱등성: 여러 건 시드 시에도 2회차 생성 0건 ────────────────────────────────

    @Test
    @DisplayName("여러 DONE 정산 백필 — 2회 연속 실행 시 2회차 created=0 (멱등 증명, 복수 건)")
    void multiSettlementPayoutBackfill_secondRunCreatesNothing() {
        LocalDate today = LocalDate.now();

        // 3건의 DONE 정산 시드
        for (int i = 0; i < 3; i++) {
            long paymentId = 30010L + i;
            long sellerId  = 300L + i;
            seedPaymentView(paymentId, sellerId);
            seedDoneSettlement(paymentId);
        }

        // ── 1회차 ────────────────────────────────────────────────────────────
        PayoutBackfillReport firstRun = backfillUseCase.backfill(today, today, null);

        assertThat(firstRun.created())
                .as("1회차: 3건 정산 → 3건 IMMEDIATE Payout 생성")
                .isEqualTo(3L);
        assertThat(firstRun.remaining()).isZero();

        // ── 2회차 ────────────────────────────────────────────────────────────
        PayoutBackfillReport secondRun = backfillUseCase.backfill(today, today, null);

        assertThat(secondRun.created())
                .as("2회차: 전량 기존 존재 → created=0 (멱등)")
                .isZero();
        assertThat(secondRun.remaining()).isZero();

        long totalPayouts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.payouts WHERE payout_type = 'IMMEDIATE'",
                Long.class);
        assertThat(totalPayouts)
                .as("두 번 실행 후 IMMEDIATE Payout 은 정확히 3건")
                .isEqualTo(3L);
    }
}
