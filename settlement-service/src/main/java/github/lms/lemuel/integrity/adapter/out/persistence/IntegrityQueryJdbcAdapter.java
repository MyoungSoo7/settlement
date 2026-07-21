package github.lms.lemuel.integrity.adapter.out.persistence;

import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.application.port.out.KeyChecksum;
import github.lms.lemuel.integrity.application.port.out.PaymentKey;
import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.StuckStateReport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 정합성 집계 어댑터 — settlement_db 로컬 테이블만 읽는 read-only 네이티브 집계.
 *
 * <p>JPA 엔티티를 재사용하지 않고 SQL 로 직접 집계한다: 여러 도메인(settlement/ledger/payout/
 * pgreconciliation)의 테이블을 한 번에 대조하는 리포트라, 특정 도메인 어댑터에 붙이면
 * 도메인 경계가 흐려진다. ID 목록은 {@value #ID_LIMIT} 건으로 절단해 리포트 비대화를 막는다.
 */
@Component
public class IntegrityQueryJdbcAdapter implements IntegrityQueryPort {

    private static final int ID_LIMIT = 20;

    private final JdbcClient jdbc;

    public IntegrityQueryJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── INV-5 원장 완전성 ──────────────────────────────────────────────────

    @Override
    public LedgerCompletenessReport ledgerCompleteness(LocalDate date, int graceMinutes,
                                                       LocalDateTime graceCutoff) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        var confirmed = jdbc.sql("""
                        SELECT count(*) AS cnt, coalesce(sum(payment_amount), 0) AS total
                        FROM settlements
                        WHERE status = 'DONE' AND confirmed_at >= :start AND confirmed_at < :end
                        """)
                .param("start", start).param("end", end)
                .query((rs, i) -> new CountTotal(rs.getLong("cnt"), money(rs, "total")))
                .single();

        var ledger = jdbc.sql("""
                        SELECT count(*) AS cnt, coalesce(sum(le.amount), 0) AS total
                        FROM ledger_entries le
                        WHERE le.reference_type = 'SETTLEMENT'
                          AND le.reference_id IN (
                              SELECT id FROM settlements
                              WHERE status = 'DONE' AND confirmed_at >= :start AND confirmed_at < :end)
                        """)
                .param("start", start).param("end", end)
                .query((rs, i) -> new CountTotal(rs.getLong("cnt"), money(rs, "total")))
                .single();

        List<Long> missing = jdbc.sql("""
                        SELECT s.id FROM settlements s
                        WHERE s.status = 'DONE' AND s.confirmed_at >= :start AND s.confirmed_at < :end
                          AND s.payment_amount > 0
                          AND s.confirmed_at <= :graceCutoff
                          AND NOT EXISTS (SELECT 1 FROM ledger_entries le
                                          WHERE le.reference_id = s.id AND le.reference_type = 'SETTLEMENT')
                        ORDER BY s.id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("start", start).param("end", end).param("graceCutoff", graceCutoff)
                .query(Long.class).list();

        long pendingWithinGrace = jdbc.sql("""
                        SELECT count(*) FROM settlements s
                        WHERE s.status = 'DONE' AND s.confirmed_at >= :start AND s.confirmed_at < :end
                          AND s.payment_amount > 0
                          AND s.confirmed_at > :graceCutoff
                          AND NOT EXISTS (SELECT 1 FROM ledger_entries le
                                          WHERE le.reference_id = s.id AND le.reference_type = 'SETTLEMENT')
                        """)
                .param("start", start).param("end", end).param("graceCutoff", graceCutoff)
                .query(Long.class).single();

        List<Long> amountMismatched = jdbc.sql("""
                        SELECT s.id FROM settlements s
                        JOIN (SELECT reference_id, sum(amount) AS total
                              FROM ledger_entries WHERE reference_type = 'SETTLEMENT'
                              GROUP BY reference_id) le ON le.reference_id = s.id
                        WHERE s.status = 'DONE' AND s.confirmed_at >= :start AND s.confirmed_at < :end
                          AND le.total <> s.payment_amount
                        ORDER BY s.id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("start", start).param("end", end)
                .query(Long.class).list();

        // 3개 출처(환불·차지백·PG대사) 조정 각각이 대응 역분개(reference_type 별)를 갖는지 대조한다.
        // 출처 id 는 서로 다른 id 공간이라 adjustment.id 로 반환해 축 간 충돌 없이 식별한다.
        List<Long> missingReverse = jdbc.sql("""
                        SELECT a.id FROM settlement_adjustments a
                        WHERE a.adjustment_date = :date AND a.created_at <= :graceCutoff
                          AND (
                                (a.refund_id IS NOT NULL AND NOT EXISTS (
                                    SELECT 1 FROM ledger_entries le
                                    WHERE le.reference_id = a.refund_id AND le.reference_type = 'REFUND'))
                             OR (a.chargeback_id IS NOT NULL AND NOT EXISTS (
                                    SELECT 1 FROM ledger_entries le
                                    WHERE le.reference_id = a.chargeback_id AND le.reference_type = 'CHARGEBACK'))
                             OR (a.reconciliation_discrepancy_id IS NOT NULL AND NOT EXISTS (
                                    SELECT 1 FROM ledger_entries le
                                    WHERE le.reference_id = a.reconciliation_discrepancy_id
                                      AND le.reference_type = 'PG_RECONCILIATION'))
                          )
                        ORDER BY a.id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("date", date).param("graceCutoff", graceCutoff)
                .query(Long.class).list();

        var outbox = ledgerOutboxSnapshot(graceCutoff.plusMinutes(graceMinutes));

        return LedgerCompletenessReport.of(date, graceMinutes,
                confirmed.count(), confirmed.total(),
                ledger.count(), ledger.total(),
                missing, pendingWithinGrace, amountMismatched, missingReverse,
                outbox.pending(), outbox.failed(), outbox.oldestPendingAgeSec());
    }

    // ── INV-6 지급 대사 ────────────────────────────────────────────────────

    @Override
    public PayoutReconReport payoutRecon(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        var confirmed = jdbc.sql("""
                        SELECT count(*) AS cnt, coalesce(sum(net_amount), 0) AS total
                        FROM settlements
                        WHERE status = 'DONE' AND confirmed_at >= :start AND confirmed_at < :end
                        """)
                .param("start", start).param("end", end)
                .query((rs, i) -> new CountTotal(rs.getLong("cnt"), money(rs, "total")))
                .single();

        var payouts = jdbc.sql("""
                        SELECT count(*) AS cnt,
                               coalesce(sum(p.amount), 0) AS total,
                               count(*) FILTER (WHERE p.status = 'COMPLETED') AS completed
                        FROM payouts p
                        WHERE p.status <> 'CANCELED'
                          AND p.settlement_id IN (
                              SELECT id FROM settlements
                              WHERE status = 'DONE' AND confirmed_at >= :start AND confirmed_at < :end)
                        """)
                .param("start", start).param("end", end)
                .query((rs, i) -> new PayoutAgg(rs.getLong("cnt"), money(rs, "total"), rs.getLong("completed")))
                .single();

        List<Long> withoutPayout = jdbc.sql("""
                        SELECT s.id FROM settlements s
                        WHERE s.status = 'DONE' AND s.confirmed_at >= :start AND s.confirmed_at < :end
                          AND NOT EXISTS (SELECT 1 FROM payouts p
                                          WHERE p.settlement_id = s.id AND p.status <> 'CANCELED')
                        ORDER BY s.id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("start", start).param("end", end)
                .query(Long.class).list();

        List<PayoutReconReport.OverpaidPayout> overpaid = jdbc.sql("""
                        SELECT p.id AS payout_id, p.settlement_id, p.amount, s.net_amount
                        FROM payouts p
                        JOIN settlements s ON s.id = p.settlement_id
                        WHERE s.status = 'DONE' AND s.confirmed_at >= :start AND s.confirmed_at < :end
                          AND p.status <> 'CANCELED'
                          AND p.amount > s.net_amount
                        ORDER BY p.id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("start", start).param("end", end)
                .query((rs, i) -> new PayoutReconReport.OverpaidPayout(
                        rs.getLong("payout_id"), rs.getLong("settlement_id"),
                        money(rs, "amount"), money(rs, "net_amount")))
                .list();

        List<Long> duplicates = jdbc.sql("""
                        SELECT p.settlement_id FROM payouts p
                        WHERE p.status <> 'CANCELED'
                          AND p.settlement_id IN (
                              SELECT id FROM settlements
                              WHERE status = 'DONE' AND confirmed_at >= :start AND confirmed_at < :end)
                        GROUP BY p.settlement_id HAVING count(*) > 1
                        LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("start", start).param("end", end)
                .query(Long.class).list();

        return PayoutReconReport.of(date, confirmed.count(), confirmed.total(),
                payouts.count(), payouts.total(), payouts.completed(),
                withoutPayout, overpaid, duplicates);
    }

    // ── INV-7 홀드백 ───────────────────────────────────────────────────────

    @Override
    public HoldbackStatusReport holdbackStatus(LocalDate today) {
        var overdue = jdbc.sql("""
                        SELECT count(*) AS cnt, coalesce(sum(holdback_amount), 0) AS total
                        FROM settlements
                        WHERE holdback_released = false AND holdback_amount > 0
                          AND holdback_release_date < :today
                        """)
                .param("today", today)
                .query((rs, i) -> new CountTotal(rs.getLong("cnt"), money(rs, "total")))
                .single();

        List<Long> overdueIds = jdbc.sql("""
                        SELECT id FROM settlements
                        WHERE holdback_released = false AND holdback_amount > 0
                          AND holdback_release_date < :today
                        ORDER BY holdback_release_date, id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("today", today)
                .query(Long.class).list();

        var totals = jdbc.sql("""
                        SELECT coalesce(sum(holdback_amount) FILTER (WHERE holdback_released = false), 0) AS held,
                               coalesce(sum(holdback_amount) FILTER (WHERE holdback_released = true), 0) AS released,
                               max(holdback_released_at) AS last_released
                        FROM settlements
                        """)
                .query((rs, i) -> new HoldbackTotals(money(rs, "held"), money(rs, "released"),
                        timestamp(rs, "last_released")))
                .single();

        return HoldbackStatusReport.of(today, overdue.count(), overdue.total(), overdueIds,
                totals.held(), totals.released(), totals.lastReleased());
    }

    // ── INV-11 상태 체류 ───────────────────────────────────────────────────

    @Override
    public StuckStateReport stuckStates(int thresholdMinutes, LocalDateTime stuckCutoff, LocalDate today) {
        List<StuckStateReport.StuckItem> stuckSettlements = jdbc.sql("""
                        SELECT id, status, updated_at AS since FROM settlements
                        WHERE status = 'PROCESSING' AND updated_at < :cutoff
                        ORDER BY updated_at LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("cutoff", stuckCutoff)
                .query(this::stuckItem).list();

        List<StuckStateReport.StuckItem> overdueConfirmations = jdbc.sql("""
                        SELECT id, status, updated_at AS since FROM settlements
                        WHERE status IN ('PENDING', 'REQUESTED', 'PROCESSING')
                          AND settlement_date < :today
                        ORDER BY settlement_date, id LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("today", today)
                .query(this::stuckItem).list();

        List<StuckStateReport.StuckPayout> stuckSending = jdbc.sql("""
                        SELECT id, settlement_id, amount, sent_at FROM payouts
                        WHERE status = 'SENDING' AND sent_at < :cutoff
                        ORDER BY sent_at LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("cutoff", stuckCutoff)
                .query((rs, i) -> new StuckStateReport.StuckPayout(
                        rs.getLong("id"), rs.getLong("settlement_id"),
                        money(rs, "amount"), timestamp(rs, "sent_at")))
                .list();

        List<StuckStateReport.StuckItem> stuckPgRuns = jdbc.sql("""
                        SELECT id, status, started_at AS since FROM pg_reconciliation_runs
                        WHERE status = 'RUNNING' AND started_at < :cutoff
                        ORDER BY started_at LIMIT %d
                        """.formatted(ID_LIMIT))
                .param("cutoff", stuckCutoff)
                .query(this::stuckItem).list();

        long stuckOutboxPending = jdbc.sql("""
                        SELECT count(*) FROM ledger_outbox
                        WHERE status = 'PENDING' AND created_at < :cutoff
                        """)
                .param("cutoff", stuckCutoff)
                .query(Long.class).single();

        long outboxFailed = jdbc.sql("SELECT count(*) FROM ledger_outbox WHERE status = 'FAILED'")
                .query(Long.class).single();

        return StuckStateReport.of(thresholdMinutes, today,
                stuckSettlements, overdueConfirmations, stuckSending, stuckPgRuns,
                stuckOutboxPending, outboxFailed);
    }

    // ── INV-8 / INV-10 ────────────────────────────────────────────────────

    @Override
    public Set<Long> adjustedRefundIds(Collection<Long> refundIds) {
        if (refundIds == null || refundIds.isEmpty()) {
            return Set.of();
        }
        List<Long> found = jdbc.sql("""
                        SELECT DISTINCT refund_id FROM settlement_adjustments
                        WHERE refund_id IN (:ids)
                        """)
                .param("ids", List.copyOf(refundIds))
                .query(Long.class).list();
        return new HashSet<>(found);
    }

    @Override
    public List<ProcessedEventCount> processedEventCounts(LocalDateTime from, LocalDateTime to) {
        return jdbc.sql("""
                        SELECT consumer_group, event_type, count(*) AS cnt
                        FROM processed_events
                        WHERE processed_at >= :from AND processed_at < :to
                        GROUP BY consumer_group, event_type
                        ORDER BY consumer_group, event_type
                        """)
                .param("from", from).param("to", to)
                .query((rs, i) -> new ProcessedEventCount(
                        rs.getString("consumer_group"), rs.getString("event_type"), rs.getLong("cnt")))
                .list();
    }

    // ── INV-12 프로젝션 행 diff (settlement_payment_view) ─────────────────

    @Override
    public KeyChecksum projectionPaymentChecksum(LocalDate date) {
        return jdbc.sql("""
                        SELECT count(*) AS cnt,
                               coalesce(sum(amount), 0) AS amount_sum,
                               coalesce(md5(string_agg(payment_id::text, ',' ORDER BY payment_id)), '') AS id_checksum
                        FROM settlement_payment_view
                        WHERE captured_at::date = :date
                        """)
                .param("date", date)
                .query((rs, i) -> new KeyChecksum(
                        rs.getLong("cnt"), money(rs, "amount_sum"), rs.getString("id_checksum")))
                .single();
    }

    @Override
    public List<PaymentKey> projectionPaymentKeys(LocalDate date, long afterId, int limit) {
        return jdbc.sql("""
                        SELECT payment_id, amount FROM settlement_payment_view
                        WHERE captured_at::date = :date AND payment_id > :afterId
                        ORDER BY payment_id LIMIT :limit
                        """)
                .param("date", date).param("afterId", afterId).param("limit", limit)
                .query((rs, i) -> new PaymentKey(rs.getLong("payment_id"), money(rs, "amount")))
                .list();
    }

    // ── 공용 ──────────────────────────────────────────────────────────────

    private OutboxSnapshot ledgerOutboxSnapshot(LocalDateTime now) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                               count(*) FILTER (WHERE status = 'FAILED') AS failed,
                               min(created_at) FILTER (WHERE status = 'PENDING') AS oldest
                        FROM ledger_outbox
                        """)
                .query((rs, i) -> {
                    LocalDateTime oldest = timestamp(rs, "oldest");
                    long ageSec = oldest == null ? 0
                            : Math.max(0, Duration.between(oldest, now).getSeconds());
                    return new OutboxSnapshot(rs.getLong("pending"), rs.getLong("failed"), ageSec);
                })
                .single();
    }

    private StuckStateReport.StuckItem stuckItem(ResultSet rs, int i) throws SQLException {
        return new StuckStateReport.StuckItem(rs.getLong("id"), rs.getString("status"),
                timestamp(rs, "since"));
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private record CountTotal(long count, BigDecimal total) {
    }

    private record PayoutAgg(long count, BigDecimal total, long completed) {
    }

    private record HoldbackTotals(BigDecimal held, BigDecimal released, LocalDateTime lastReleased) {
    }

    private record OutboxSnapshot(long pending, long failed, long oldestPendingAgeSec) {
    }
}
