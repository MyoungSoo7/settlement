package github.lms.lemuel.report.adapter.out.persistence;

import github.lms.lemuel.report.application.port.out.LoadPeriodReconciliationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 리포트 기간 대사용 JDBC aggregation 어댑터.
 *
 * <p>설계 포인트:
 * <ul>
 *   <li>settlement 의 {@code DailyTotalsJdbcAdapter} 와 동일한 테이블(opslab.payments / refunds / settlements)을
 *       조회하지만, 기간 범위(from-to)를 인자로 받는다.</li>
 *   <li>report 도메인 경계 내에서 직접 JDBC 사용 — settlement 서비스/포트에 의존하지 않는다.</li>
 *   <li>각 쿼리는 {@code SUM(...) COALESCE 0} 패턴으로 NULL 안전.</li>
 * </ul>
 */
@Repository
public class PeriodReconciliationJdbcAdapter implements LoadPeriodReconciliationPort {

    private final JdbcTemplate jdbcTemplate;

    public PeriodReconciliationJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BigDecimal sumCapturedPayments(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COALESCE(SUM(amount), 0)
                FROM opslab.payments
                WHERE status = 'CAPTURED'
                  AND captured_at::date BETWEEN ? AND ?
                """;
        return queryDecimal(sql, from, to);
    }

    @Override
    public BigDecimal sumCompletedRefunds(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COALESCE(SUM(amount), 0)
                FROM opslab.refunds
                WHERE status = 'COMPLETED'
                  AND completed_at::date BETWEEN ? AND ?
                """;
        return queryDecimal(sql, from, to);
    }

    @Override
    public BigDecimal sumSettlementNet(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COALESCE(SUM(net_amount), 0)
                FROM opslab.settlements
                WHERE settlement_date BETWEEN ? AND ?
                  AND status <> 'CANCELED'
                """;
        return queryDecimal(sql, from, to);
    }

    @Override
    public BigDecimal sumSettlementCommission(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COALESCE(SUM(commission), 0)
                FROM opslab.settlements
                WHERE settlement_date BETWEEN ? AND ?
                  AND status <> 'CANCELED'
                """;
        return queryDecimal(sql, from, to);
    }

    @Override
    public BigDecimal sumAdjustmentsAbsolute(LocalDate from, LocalDate to) {
        // adjustments.amount 는 CHECK 로 항상 음수. 절대값을 위해 부호 반전 후 합산.
        String sql = """
                SELECT COALESCE(-SUM(amount), 0)
                FROM opslab.settlement_adjustments
                WHERE adjustment_date BETWEEN ? AND ?
                """;
        return queryDecimal(sql, from, to);
    }

    @Override
    public BigDecimal sumRefundsLinkedToAdjustments(LocalDate from, LocalDate to) {
        // 기간 내 조정에 연결된 refunds 만 집계 (COMPLETED 만).
        String sql = """
                SELECT COALESCE(SUM(r.amount), 0)
                FROM opslab.refunds r
                JOIN opslab.settlement_adjustments sa ON sa.refund_id = r.id
                WHERE sa.adjustment_date BETWEEN ? AND ?
                  AND r.status = 'COMPLETED'
                """;
        return queryDecimal(sql, from, to);
    }

    @Override
    public long countPaymentCapturedPublished(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(*)
                FROM opslab.outbox_events
                WHERE event_type = 'PaymentCaptured'
                  AND status = 'PUBLISHED'
                  AND published_at::date BETWEEN ? AND ?
                """;
        Long result = jdbcTemplate.queryForObject(sql, Long.class, from, to);
        return result != null ? result : 0L;
    }

    @Override
    public long countSettlementsCreated(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(*)
                FROM opslab.settlements
                WHERE created_at::date BETWEEN ? AND ?
                """;
        Long result = jdbcTemplate.queryForObject(sql, Long.class, from, to);
        return result != null ? result : 0L;
    }

    private BigDecimal queryDecimal(String sql, LocalDate from, LocalDate to) {
        BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, from, to);
        return result != null ? result : BigDecimal.ZERO;
    }
}
