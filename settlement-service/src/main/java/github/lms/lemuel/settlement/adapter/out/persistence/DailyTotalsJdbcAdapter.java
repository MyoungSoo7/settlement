package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.recon.OrderReconClient;
import github.lms.lemuel.settlement.application.port.out.LoadDailyTotalsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일일 대사용 집계 어댑터 (ADR 0020 Phase 5 self-totals).
 *
 * <p>order 원천 합계(CAPTURED 결제·COMPLETED 환불)는 {@link OrderReconClient} 로 order 의 내부 API
 * 에서 받아오고, settlement 자기 합계(net·commission)는 자기 settlement_db 의 {@code settlements} 에서
 * 직접 집계한다. → settlement 가 order DB 를 직접 읽지 않는다(cross-DB 연결 0).
 */
@Repository
public class DailyTotalsJdbcAdapter implements LoadDailyTotalsPort {

    private final JdbcTemplate jdbcTemplate;
    private final OrderReconClient orderReconClient;

    public DailyTotalsJdbcAdapter(JdbcTemplate jdbcTemplate, OrderReconClient orderReconClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderReconClient = orderReconClient;
    }

    @Override
    public BigDecimal sumCapturedPayments(LocalDate date) {
        return nz(orderReconClient.dailyTotals(date).capturedPayments());
    }

    @Override
    public BigDecimal sumCompletedRefunds(LocalDate date) {
        return nz(orderReconClient.dailyTotals(date).completedRefunds());
    }

    @Override
    public BigDecimal sumSettlementNet(LocalDate date) {
        // 생성 대사: created_at 기준. settlement_date(지급 예정일, T+N 영업일)로 자르면
        // 캡처일 D 의 정산이 D+N 버킷으로 빠져 대사가 구조적으로 깨진다.
        return queryDecimal("""
                SELECT COALESCE(SUM(net_amount), 0)
                FROM settlements
                WHERE created_at::date = ?
                  AND status <> 'CANCELED'
                """, date);
    }

    @Override
    public BigDecimal sumSettlementCommission(LocalDate date) {
        return queryDecimal("""
                SELECT COALESCE(SUM(commission), 0)
                FROM settlements
                WHERE created_at::date = ?
                  AND status <> 'CANCELED'
                """, date);
    }

    @Override
    public BigDecimal sumRefundAdjustments(LocalDate date) {
        // 환불 조정(ADR 0004)은 음수 기록 — 양수 환산해 환불 축과 직접 비교한다.
        // chargeback 조정은 환불 축이 아니므로 refund_id 연결분만 집계.
        return queryDecimal("""
                SELECT COALESCE(SUM(-amount), 0)
                FROM settlement_adjustments
                WHERE refund_id IS NOT NULL
                  AND created_at::date = ?
                """, date);
    }

    private BigDecimal queryDecimal(String sql, LocalDate date) {
        BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, date);
        return result != null ? result : BigDecimal.ZERO;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
