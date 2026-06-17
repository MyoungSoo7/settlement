package github.lms.lemuel.report.adapter.out.persistence;

import github.lms.lemuel.recon.OrderReconClient;
import github.lms.lemuel.report.application.port.out.LoadPeriodReconciliationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 리포트 기간 대사용 집계 어댑터 (ADR 0020 Phase 5 self-totals).
 *
 * <p>order 원천 합계/건수(CAPTURED 결제·COMPLETED 환불·PaymentCaptured PUBLISHED 건수)는
 * {@link OrderReconClient} 로 order 내부 API 에서 받아오고, settlement 자기 데이터(settlements·
 * settlement_adjustments)는 자기 settlement_db 에서 직접 집계한다 → cross-DB 연결 0.
 *
 * <p><b>cross-DB JOIN 분해</b>: 기존 {@code refunds JOIN settlement_adjustments} 는 두 DB 에 걸쳐
 * 단일 SQL 로 불가능하므로, settlement 가 자기 adjustments 의 refund_id 집합을 산출해 order 에
 * "이 환불들의 COMPLETED 합계"를 질의하는 2단계로 분해한다.
 */
@Repository
public class PeriodReconciliationJdbcAdapter implements LoadPeriodReconciliationPort {

    private final JdbcTemplate jdbcTemplate;
    private final OrderReconClient orderReconClient;

    public PeriodReconciliationJdbcAdapter(JdbcTemplate jdbcTemplate, OrderReconClient orderReconClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderReconClient = orderReconClient;
    }

    // ---------- order 원천 (자기 DB 아님 → 내부 API) ----------

    @Override
    public BigDecimal sumCapturedPayments(LocalDate from, LocalDate to) {
        return nz(orderReconClient.periodTotals(from, to).capturedPayments());
    }

    @Override
    public BigDecimal sumCompletedRefunds(LocalDate from, LocalDate to) {
        return nz(orderReconClient.periodTotals(from, to).completedRefunds());
    }

    @Override
    public long countPaymentCapturedPublished(LocalDate from, LocalDate to) {
        return orderReconClient.periodTotals(from, to).paymentCapturedPublishedCount();
    }

    // ---------- settlement 자기 데이터 (settlement_db) ----------

    @Override
    public BigDecimal sumSettlementNet(LocalDate from, LocalDate to) {
        return decimal("""
                SELECT COALESCE(SUM(net_amount), 0)
                FROM settlements
                WHERE settlement_date BETWEEN ? AND ?
                  AND status <> 'CANCELED'
                """, from, to);
    }

    @Override
    public BigDecimal sumSettlementCommission(LocalDate from, LocalDate to) {
        return decimal("""
                SELECT COALESCE(SUM(commission), 0)
                FROM settlements
                WHERE settlement_date BETWEEN ? AND ?
                  AND status <> 'CANCELED'
                """, from, to);
    }

    @Override
    public BigDecimal sumAdjustmentsAbsolute(LocalDate from, LocalDate to) {
        // adjustments.amount 는 CHECK 로 항상 음수. 절대값을 위해 부호 반전 후 합산.
        return decimal("""
                SELECT COALESCE(-SUM(amount), 0)
                FROM settlement_adjustments
                WHERE adjustment_date BETWEEN ? AND ?
                """, from, to);
    }

    @Override
    public long countSettlementsCreated(LocalDate from, LocalDate to) {
        Long result = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM settlements
                WHERE created_at::date BETWEEN ? AND ?
                """, Long.class, from, to);
        return result != null ? result : 0L;
    }

    // ---------- cross-DB JOIN 분해 (settlement adjustments → order refunds) ----------

    @Override
    public BigDecimal sumRefundsLinkedToAdjustments(LocalDate from, LocalDate to) {
        // 1) settlement 자기 DB: 기간 내 조정이 참조하는 refund_id 집합
        List<Long> refundIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT refund_id
                FROM settlement_adjustments
                WHERE adjustment_date BETWEEN ? AND ?
                  AND refund_id IS NOT NULL
                """, Long.class, from, to);
        // 2) order 원천: 그 refund 들의 COMPLETED 합계
        return nz(orderReconClient.refundsCompletedSum(refundIds));
    }

    private BigDecimal decimal(String sql, LocalDate from, LocalDate to) {
        BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, from, to);
        return result != null ? result : BigDecimal.ZERO;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
