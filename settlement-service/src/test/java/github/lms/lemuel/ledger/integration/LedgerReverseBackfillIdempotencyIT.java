package github.lms.lemuel.ledger.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.ledger.application.port.in.BackfillMissingReverseUseCase;
import github.lms.lemuel.ledger.application.port.in.ProcessLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import github.lms.lemuel.ledger.domain.LedgerReverseBackfillReport;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 역분개 누락 백필 멱등성 통합 테스트 (AC: 2회 연속 실행 시 2회차 변경 0건).
 *
 * <p>검증 시나리오:
 * <ol>
 *   <li>차지백 조정·PG 대사 조정을 심는다 (역분개 없음).</li>
 *   <li>1회차 백필 실행 → ledger_outbox 에 역분개 작업 적재.</li>
 *   <li>아웃박스 폴러 시뮬레이션 → ledger_entries 생성.</li>
 *   <li>2회차 백필 실행 → totalEnqueued=0 (ledger_entries 가 이미 있으므로 탐지 쿼리가 빈 결과 반환).</li>
 * </ol>
 *
 * <p>멱등 메커니즘: {@code enqueueReversePage} 탐지 SQL 이
 * {@code NOT EXISTS (ledger_entries WHERE reference_id=... AND reference_type=...)} 로
 * 이미 존재하는 역분개를 제외한다. 폴러가 outbox 를 처리해 entries 를 채우면
 * 2회차 백필은 대상 건 0건 → enqueuedChargeback=0·enqueuedReconciliation=0.
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
class LedgerReverseBackfillIdempotencyIT {

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

    @Autowired BackfillMissingReverseUseCase backfillUseCase;
    @Autowired ProcessLedgerOutboxPort processPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        // TRUNCATE: POSTED 원장·DONE 정산 불변 트리거를 우회하고 FK CASCADE 로 연계 테이블도 비운다.
        jdbc.execute("TRUNCATE TABLE public.ledger_outbox, public.ledger_entries, "
                + "public.settlement_adjustments, public.chargebacks, "
                + "public.pg_reconciliation_runs, public.pg_reconciliation_discrepancies, "
                + "public.settlements RESTART IDENTITY CASCADE");
    }

    /** 정산 시드 — settlement_adjustments 의 FK(settlement_id) 충족용. */
    private void seedSettlement(long id, String net) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, ?, 10000.00, 0.00, 300.00, 0.0300, ?, 0.00, 0.0000, false,
                        CURRENT_DATE, 'DONE', now(), 0, now(), now())
                """, id, id, id + 1, new BigDecimal(net));
    }

    /** 차지백 부모 행 시드 (settlement_adjustments.chargeback_id FK 충족). */
    private void seedChargeback(long id, long paymentId, String amount) {
        jdbc.update("INSERT INTO public.chargebacks "
                        + "(id, payment_id, amount, reason_code, status, source, raised_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'FRAUD', 'ACCEPTED', 'PG_WEBHOOK', now(), now(), now())",
                id, paymentId, new BigDecimal(amount));
    }

    /** PG 대사 불일치 부모 행 시드 (settlement_adjustments.reconciliation_discrepancy_id FK 충족). */
    private void seedDiscrepancy(long runId, long discrepancyId, long paymentId) {
        jdbc.update("INSERT INTO public.pg_reconciliation_runs "
                        + "(id, pg_provider, target_date, file_name, status, started_at, "
                        + " total_pg_rows, total_internal_rows, matched_count, discrepancy_count, auto_corrected_count) "
                        + "VALUES (?, 'TOSS', CURRENT_DATE, 'recon.csv', 'COMPLETED', now(), 0, 0, 0, 1, 0)", runId);
        jdbc.update("INSERT INTO public.pg_reconciliation_discrepancies "
                        + "(id, run_id, type, payment_id, status, created_at) "
                        + "VALUES (?, ?, 'AMOUNT_MISMATCH', ?, 'APPROVED', now())", discrepancyId, runId, paymentId);
    }

    /** 조정 행 시드 (grace 경과 — 생성 1시간 전). */
    private void seedAdjustment(long settlementId, String sourceColumn, long sourceId, String amount) {
        jdbc.update("INSERT INTO public.settlement_adjustments "
                        + "(settlement_id, " + sourceColumn + ", amount, status, adjustment_date, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PENDING', CURRENT_DATE, now() - interval '1 hour', now())",
                settlementId, sourceId, new BigDecimal(amount));
    }

    /** ledger_outbox PENDING 작업을 모두 처리 (아웃박스 폴러 시뮬레이션). */
    private void processAllPending() {
        for (LedgerOutboxTask t : processPort.fetchPending(200)) {
            processPort.execute(t);
            processPort.markDone(t.id());
        }
    }

    private int ledgerCount(long referenceId, String referenceType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.ledger_entries WHERE reference_id = ? AND reference_type = ?",
                Integer.class, referenceId, referenceType);
    }

    // ── 멱등성: 차지백 역분개 백필 ────────────────────────────────────────────────

    @Test
    @DisplayName("차지백 역분개 백필 — 폴러 처리 후 2회차 totalEnqueued=0 (멱등 증명)")
    void chargebackReverseBackfill_afterPollerProcessing_secondRunEnqueuesNothing() {
        seedSettlement(40001L, "9700");
        seedChargeback(50001L, 40001L, "5000");
        seedAdjustment(40001L, "chargeback_id", 50001L, "-5000");

        // ── 1회차 백필: ledger_outbox 에 역분개 작업 1건 적재 ─────────────────
        LedgerReverseBackfillReport firstRun = backfillUseCase.backfillMissingReverse(null);

        assertThat(firstRun.enqueuedChargeback())
                .as("1회차: 차지백 역분개 1건 적재")
                .isEqualTo(1L);
        assertThat(firstRun.totalEnqueued()).isEqualTo(1L);

        // ── 아웃박스 처리: ledger_entries 에 실제 역분개 생성 ──────────────────
        processAllPending();

        // 차지백 역분개는 (net + commission) 2행 생성
        assertThat(ledgerCount(50001L, "CHARGEBACK"))
                .as("폴러 처리 후 차지백 역분개 2행 생성됨")
                .isEqualTo(2);

        // ── 2회차 백필: ledger_entries 존재 → NOT EXISTS 로 제외 → 0건 적재 ──
        LedgerReverseBackfillReport secondRun = backfillUseCase.backfillMissingReverse(null);

        assertThat(secondRun.enqueuedChargeback())
                .as("2회차: ledger_entries 존재 → 차지백 역분개 적재 0건 (멱등)")
                .isZero();
        assertThat(secondRun.enqueuedReconciliation())
                .as("2회차: PG 대사 역분개 적재 0건")
                .isZero();
        assertThat(secondRun.totalEnqueued())
                .as("2회차: 전체 적재 0건 (멱등 증명)")
                .isZero();
        assertThat(secondRun.remainingMissing())
                .as("2회차: 역분개 누락 잔여 0건")
                .isZero();

        // DB 상에서도 ledger_entries 건수 불변 확인 (추가 생성 없음)
        assertThat(ledgerCount(50001L, "CHARGEBACK"))
                .as("두 번 실행 후에도 차지백 역분개는 2건 그대로")
                .isEqualTo(2);
    }

    // ── 멱등성: PG 대사 역분개 백필 ─────────────────────────────────────────────

    @Test
    @DisplayName("PG 대사 역분개 백필 — 폴러 처리 후 2회차 totalEnqueued=0 (멱등 증명)")
    void reconReverseBackfill_afterPollerProcessing_secondRunEnqueuesNothing() {
        seedSettlement(40002L, "96500");
        seedDiscrepancy(60001L, 70001L, 40002L);
        seedAdjustment(40002L, "reconciliation_discrepancy_id", 70001L, "-1000");

        // ── 1회차 백필 ────────────────────────────────────────────────────────
        LedgerReverseBackfillReport firstRun = backfillUseCase.backfillMissingReverse(null);

        assertThat(firstRun.enqueuedReconciliation())
                .as("1회차: PG 대사 역분개 1건 적재")
                .isEqualTo(1L);
        assertThat(firstRun.totalEnqueued()).isEqualTo(1L);

        // ── 아웃박스 처리 ──────────────────────────────────────────────────────
        processAllPending();
        assertThat(ledgerCount(70001L, "PG_RECONCILIATION"))
                .as("폴러 처리 후 PG 대사 역분개 2행 생성됨")
                .isEqualTo(2);

        // ── 2회차 백필 ────────────────────────────────────────────────────────
        LedgerReverseBackfillReport secondRun = backfillUseCase.backfillMissingReverse(null);

        assertThat(secondRun.totalEnqueued())
                .as("2회차: ledger_entries 존재 → 적재 0건 (멱등 증명)")
                .isZero();
        assertThat(secondRun.remainingMissing()).isZero();

        // DB 상에서도 건수 불변
        assertThat(ledgerCount(70001L, "PG_RECONCILIATION"))
                .as("두 번 실행 후에도 PG 대사 역분개는 2건 그대로")
                .isEqualTo(2);
    }

    // ── 멱등성: 차지백 + PG 대사 복합 백필 ───────────────────────────────────────

    @Test
    @DisplayName("차지백 + PG 대사 혼합 백필 — 폴러 처리 후 2회차 totalEnqueued=0 (복합 멱등 증명)")
    void mixedReverseBackfill_afterPollerProcessing_secondRunEnqueuesNothing() {
        // 차지백 조정
        seedSettlement(40003L, "9700");
        seedChargeback(50003L, 40003L, "3000");
        seedAdjustment(40003L, "chargeback_id", 50003L, "-3000");

        // PG 대사 조정
        seedSettlement(40004L, "96500");
        seedDiscrepancy(60002L, 70002L, 40004L);
        seedAdjustment(40004L, "reconciliation_discrepancy_id", 70002L, "-500");

        // ── 1회차 백필: 차지백 1건 + PG 대사 1건 = 합계 2건 적재 ──────────────
        LedgerReverseBackfillReport firstRun = backfillUseCase.backfillMissingReverse(null);

        assertThat(firstRun.enqueuedChargeback())
                .as("1회차: 차지백 역분개 1건")
                .isEqualTo(1L);
        assertThat(firstRun.enqueuedReconciliation())
                .as("1회차: PG 대사 역분개 1건")
                .isEqualTo(1L);
        assertThat(firstRun.totalEnqueued())
                .as("1회차: 합계 2건 적재")
                .isEqualTo(2L);

        // ── 아웃박스 처리 ──────────────────────────────────────────────────────
        processAllPending();

        assertThat(ledgerCount(50003L, "CHARGEBACK")).isEqualTo(2);
        assertThat(ledgerCount(70002L, "PG_RECONCILIATION")).isEqualTo(2);

        // ── 2회차 백필 ────────────────────────────────────────────────────────
        LedgerReverseBackfillReport secondRun = backfillUseCase.backfillMissingReverse(null);

        assertThat(secondRun.enqueuedChargeback())
                .as("2회차: 차지백 역분개 0건 (멱등)")
                .isZero();
        assertThat(secondRun.enqueuedReconciliation())
                .as("2회차: PG 대사 역분개 0건 (멱등)")
                .isZero();
        assertThat(secondRun.totalEnqueued())
                .as("2회차: 전체 적재 0건 — 두 백필 모두 멱등 증명")
                .isZero();
        assertThat(secondRun.remainingMissing())
                .as("2회차: 누락 잔여 0건")
                .isZero();

        // DB 건수 불변 (추가 생성 없음)
        assertThat(ledgerCount(50003L, "CHARGEBACK")).isEqualTo(2);
        assertThat(ledgerCount(70002L, "PG_RECONCILIATION")).isEqualTo(2);
    }
}
