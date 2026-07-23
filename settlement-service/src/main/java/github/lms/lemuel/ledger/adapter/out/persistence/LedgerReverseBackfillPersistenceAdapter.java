package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LedgerReverseBackfillPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 원장 역분개 누락 백필 어댑터 — INV-5 기준 탐지 + 페이지 단위 아웃박스 적재.
 *
 * <p>탐지 SQL 은 {@code IntegrityQueryJdbcAdapter.ledgerCompleteness} 의
 * {@code missingReverse} 서브쿼리와 동일 기준을 재사용한다:
 * <ul>
 *   <li>{@code chargeback_id IS NOT NULL} 이고 {@code ledger_entries} 에 CHARGEBACK 역분개 없음</li>
 *   <li>{@code reconciliation_discrepancy_id IS NOT NULL} 이고 PG_RECONCILIATION 역분개 없음</li>
 * </ul>
 * grace window 미적용(전체 기간 대상), {@code afterId} 커서로 페이지네이션한다.
 *
 * <p>{@code enqueueReversePage} 는 {@code @Transactional} 로 선언되어 있어 쿼리 + 아웃박스 INSERT
 * 가 한 트랜잭션에 커밋된다(페이지 단위 커밋). 서비스의 루프에서 반복 호출되며,
 * 커밋 후에도 폴러 미처리 시 같은 항목이 다시 조회될 수 있지만
 * {@code ReverseEntryService.existsByReference} + {@code uq_ledger_reference_accounts}
 * UNIQUE 제약으로 이중 분개가 차단된다(멱등).
 */
@Component
public class LedgerReverseBackfillPersistenceAdapter implements LedgerReverseBackfillPort {

    private final JdbcClient jdbc;
    private final SaveLedgerOutboxPort saveOutboxPort;

    public LedgerReverseBackfillPersistenceAdapter(JdbcClient jdbc,
                                                    SaveLedgerOutboxPort saveOutboxPort) {
        this.jdbc = jdbc;
        this.saveOutboxPort = saveOutboxPort;
    }

    // ── 탐지: INV-5 missingReverse 기준 전체 건수 ─────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long countMissingReverseAdjustments() {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM settlement_adjustments a
                        WHERE (
                              (a.chargeback_id IS NOT NULL AND NOT EXISTS (
                                  SELECT 1 FROM ledger_entries le
                                  WHERE le.reference_id = a.chargeback_id
                                    AND le.reference_type = 'CHARGEBACK'))
                           OR (a.reconciliation_discrepancy_id IS NOT NULL AND NOT EXISTS (
                                  SELECT 1 FROM ledger_entries le
                                  WHERE le.reference_id = a.reconciliation_discrepancy_id
                                    AND le.reference_type = 'PG_RECONCILIATION'))
                        )
                        """)
                .query(Long.class)
                .single();
    }

    // ── 정정: 페이지 단위 조회 + ledger_outbox 적재 ───────────────────────────

    /**
     * INV-5 기준({@code missingReverse}) 으로 {@code id > afterId} 인 항목을
     * 최대 {@code pageSize} 건 조회해 역분개 아웃박스 작업을 적재하고 커밋한다.
     *
     * <p>트랜잭션: {@code @Transactional} — 쿼리와 outbox INSERT 가 같은 커밋에 묶인다.
     * 서비스가 트랜잭션 없이 반복 호출해 각 페이지가 독립 커밋으로 보존된다.
     */
    @Override
    @Transactional
    public PageResult enqueueReversePage(long afterId, int pageSize) {
        List<MissingRow> rows = jdbc.sql("""
                        SELECT a.id,
                               a.settlement_id,
                               a.chargeback_id,
                               a.reconciliation_discrepancy_id,
                               a.amount,
                               a.adjustment_date
                        FROM settlement_adjustments a
                        WHERE a.id > :afterId
                          AND (
                              (a.chargeback_id IS NOT NULL AND NOT EXISTS (
                                  SELECT 1 FROM ledger_entries le
                                  WHERE le.reference_id = a.chargeback_id
                                    AND le.reference_type = 'CHARGEBACK'))
                           OR (a.reconciliation_discrepancy_id IS NOT NULL AND NOT EXISTS (
                                  SELECT 1 FROM ledger_entries le
                                  WHERE le.reference_id = a.reconciliation_discrepancy_id
                                    AND le.reference_type = 'PG_RECONCILIATION'))
                          )
                        ORDER BY a.id
                        LIMIT :limit
                        """)
                .param("afterId", afterId)
                .param("limit", pageSize)
                .query(LedgerReverseBackfillPersistenceAdapter::toMissingRow)
                .list();

        if (rows.isEmpty()) {
            return PageResult.empty(afterId);
        }

        List<LedgerOutboxTask> tasks = new ArrayList<>(rows.size());
        int chargebackCount = 0;
        int reconCount = 0;
        long lastId = afterId;

        for (MissingRow row : rows) {
            BigDecimal absAmount = row.amount().abs();
            if (row.chargebackId() != null) {
                tasks.add(LedgerOutboxTask.reverseChargeback(
                        row.settlementId(), row.chargebackId(), absAmount, row.adjustmentDate()));
                chargebackCount++;
            } else if (row.reconciliationDiscrepancyId() != null) {
                tasks.add(LedgerOutboxTask.reverseReconciliation(
                        row.settlementId(), row.reconciliationDiscrepancyId(), absAmount, row.adjustmentDate()));
                reconCount++;
            }
            // 두 출처 모두 null 인 행은 위 WHERE 절이 제외하므로 이 분기에 오지 않는다.
            lastId = row.id();
        }

        if (!tasks.isEmpty()) {
            saveOutboxPort.saveAll(tasks);
        }

        return new PageResult(rows.size(), lastId, chargebackCount, reconCount);
    }

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    private record MissingRow(
            long id,
            long settlementId,
            Long chargebackId,
            Long reconciliationDiscrepancyId,
            BigDecimal amount,
            LocalDate adjustmentDate) {
    }

    private static MissingRow toMissingRow(ResultSet rs, int rowNum) throws SQLException {
        Long cb = rs.getObject("chargeback_id", Long.class);
        Long rd = rs.getObject("reconciliation_discrepancy_id", Long.class);
        BigDecimal amount = rs.getBigDecimal("amount");
        LocalDate date = rs.getObject("adjustment_date", LocalDate.class);
        return new MissingRow(rs.getLong("id"), rs.getLong("settlement_id"), cb, rd, amount, date);
    }
}
