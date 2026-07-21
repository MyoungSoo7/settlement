package github.lms.lemuel.ledger.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.ledger.application.port.in.ProcessLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seed-p0-2 — 차지백·PG 대사 조정의 원장 역분개 연동을 실 PostgreSQL(Flyway validate)로 검증한다.
 *
 * <p>Flyway V20260722120000(3개 CHECK 확장)이 적용된 실 스키마로 부팅한다. 검증(시드 AC):
 * <ol>
 *   <li>AC-3: 동일 출처(차지백/대사) 역분개 작업의 재적재·재실행에도 원장 분개가 유형별로 정확히 2건.</li>
 *   <li>AC-4: INV-5(원장 완전성)가 차지백·PG 대사 조정의 역분개 누락을 감지하고, 역분개가 채워지면 해소된다.</li>
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
class LedgerReverseSourcesIntegrationIT {

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

    @Autowired EnqueueLedgerTaskPort enqueuePort;
    @Autowired ProcessLedgerOutboxPort processPort;
    @Autowired IntegrityQueryPort integrityPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        // POSTED 원장·DONE 정산 불변성 트리거가 DELETE 를 막으므로 TRUNCATE(트리거 우회)로 정리한다.
        // CASCADE 로 pg_reconciliation_runs → discrepancies 까지 함께 비운다.
        jdbc.execute("TRUNCATE TABLE public.ledger_outbox, public.ledger_entries, "
                + "public.settlement_adjustments, public.chargebacks, "
                + "public.pg_reconciliation_runs, public.pg_reconciliation_discrepancies, "
                + "public.settlements RESTART IDENTITY CASCADE");
    }

    private void seedChargeback(long id, long paymentId, String amount) {
        jdbc.update("INSERT INTO public.chargebacks "
                        + "(id, payment_id, amount, reason_code, status, source, raised_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'FRAUD', 'ACCEPTED', 'PG_WEBHOOK', now(), now(), now())",
                id, paymentId, new BigDecimal(amount));
    }

    private void seedDiscrepancy(long runId, long discrepancyId, long paymentId) {
        jdbc.update("INSERT INTO public.pg_reconciliation_runs "
                        + "(id, pg_provider, target_date, file_name, status, started_at, "
                        + " total_pg_rows, total_internal_rows, matched_count, discrepancy_count, auto_corrected_count) "
                        + "VALUES (?, 'TOSS', CURRENT_DATE, 'recon.csv', 'COMPLETED', now(), 0, 0, 0, 1, 0)", runId);
        jdbc.update("INSERT INTO public.pg_reconciliation_discrepancies "
                        + "(id, run_id, type, payment_id, status, created_at) "
                        + "VALUES (?, ?, 'AMOUNT_MISMATCH', ?, 'APPROVED', now())", discrepancyId, runId, paymentId);
    }

    private void seedSettlement(long id, String payment, String commission, String net, String status) {
        jdbc.update("""
                INSERT INTO public.settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   confirmed_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0.00, ?, 0.0300, ?, 0.00, 0.0000, false, CURRENT_DATE, ?,
                        now(), 0, now(), now())
                """, id, id, id + 1, new BigDecimal(payment), new BigDecimal(commission),
                new BigDecimal(net), status);
    }

    /** 출처별 음수 조정 1건을 심는다. createdAtSql 로 grace 경계를 제어한다. */
    private void seedAdjustment(long settlementId, String sourceColumn, long sourceId,
                                String amount, LocalDate date, String createdAtSql) {
        jdbc.update("INSERT INTO public.settlement_adjustments "
                        + "(settlement_id, " + sourceColumn + ", amount, status, adjustment_date, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PENDING', ?, " + createdAtSql + ", now())",
                settlementId, sourceId, new BigDecimal(amount), date);
    }

    private int ledgerCount(long referenceId, String referenceType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.ledger_entries WHERE reference_id = ? AND reference_type = ?",
                Integer.class, referenceId, referenceType);
    }

    private void processAllPending() {
        for (LedgerOutboxTask t : processPort.fetchPending(50)) {
            processPort.execute(t);
            processPort.markDone(t.id());
        }
    }

    // ── AC-3: 재적재·재실행에도 역분개 중복 0 ────────────────────────────────
    @Test
    @DisplayName("AC-3: 차지백 역분개는 재적재·재실행에도 (chargebackId, CHARGEBACK) 분개가 정확히 2건")
    void chargebackReverse_isIdempotentAcrossReenqueueAndReexecute() {
        seedSettlement(6001L, "10000", "300", "9700", "DONE");
        long chargebackId = 8001L;

        // 최초 적재 + 처리 → net/commission 2 row.
        tx.executeWithoutResult(t -> enqueuePort.enqueueReverseChargeback(
                6001L, chargebackId, new BigDecimal("10000"), LocalDate.now()));
        processAllPending();

        // 같은 작업을 다시 적재(재시도)하고 처리해도 existsByReference 로 skip.
        tx.executeWithoutResult(t -> enqueuePort.enqueueReverseChargeback(
                6001L, chargebackId, new BigDecimal("10000"), LocalDate.now()));
        processAllPending();

        // 같은 task 를 이중 전달(재실행)해도 중복 없음.
        List<LedgerOutboxTask> again = List.of(
                LedgerOutboxTask.reverseChargeback(6001L, chargebackId, new BigDecimal("10000"), LocalDate.now()));
        processPort.execute(again.get(0));

        assertThat(ledgerCount(chargebackId, "CHARGEBACK")).isEqualTo(2);
        assertThat(ledgerCount(chargebackId, "REFUND")).isZero();
    }

    @Test
    @DisplayName("AC-3: PG 대사 역분개도 재적재·재실행에 (discrepancyId, PG_RECONCILIATION) 분개가 정확히 2건")
    void reconReverse_isIdempotent() {
        seedSettlement(6002L, "100000", "3500", "96500", "REQUESTED");
        long discrepancyId = 9001L;

        tx.executeWithoutResult(t -> enqueuePort.enqueueReverseReconciliation(
                6002L, discrepancyId, new BigDecimal("1000"), LocalDate.now()));
        processAllPending();
        tx.executeWithoutResult(t -> enqueuePort.enqueueReverseReconciliation(
                6002L, discrepancyId, new BigDecimal("1000"), LocalDate.now()));
        processAllPending();

        // 1,000 clawback 을 수수료율 3.5% 로 분해 → commission 35 + net 965 = 2 row.
        assertThat(ledgerCount(discrepancyId, "PG_RECONCILIATION")).isEqualTo(2);
    }

    // ── AC-4: INV-5 가 차지백·PG 대사 역분개 누락을 감지 ──────────────────────
    @Test
    @DisplayName("AC-4: INV-5 는 차지백·PG 대사 조정의 역분개 누락을 감지하고, 채워지면 해소된다")
    void inv5_detectsMissingReverseForChargebackAndRecon() {
        LocalDate today = LocalDate.now();
        seedSettlement(6003L, "10000", "300", "9700", "REQUESTED");
        seedSettlement(6004L, "100000", "3500", "96500", "REQUESTED");
        // 출처 부모 행(FK 충족): 차지백 1건 + PG 대사 실행/불일치 1건.
        seedChargeback(8003L, 6003L, "5000");
        seedDiscrepancy(9900L, 9004L, 6004L);
        // grace 경과(1시간 전 생성)한 조정 2건 — 아직 역분개 없음.
        seedAdjustment(6003L, "chargeback_id", 8003L, "-5000", today, "now() - interval '1 hour'");
        seedAdjustment(6004L, "reconciliation_discrepancy_id", 9004L, "-1000", today, "now() - interval '1 hour'");

        LocalDateTime graceCutoff = LocalDateTime.now();
        LedgerCompletenessReport before = integrityPort.ledgerCompleteness(today, 15, graceCutoff);
        assertThat(before.ok()).isFalse();
        assertThat(before.missingReverseAdjustmentIds()).hasSize(2);

        // 두 출처의 역분개를 채운다.
        tx.executeWithoutResult(t -> {
            enqueuePort.enqueueReverseChargeback(6003L, 8003L, new BigDecimal("5000"), today);
            enqueuePort.enqueueReverseReconciliation(6004L, 9004L, new BigDecimal("1000"), today);
        });
        processAllPending();

        LedgerCompletenessReport after = integrityPort.ledgerCompleteness(today, 15, LocalDateTime.now());
        assertThat(after.missingReverseAdjustmentIds()).isEmpty();
    }
}
