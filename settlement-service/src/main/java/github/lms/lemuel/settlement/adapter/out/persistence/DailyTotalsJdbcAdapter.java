package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.recon.OrderReconClient;
import github.lms.lemuel.settlement.application.port.out.LoadDailyTotalsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일일 대사용 집계 어댑터 (ADR 0020 Phase 5 self-totals) — 캡처일 기준 양축.
 *
 * <p>order 원천(캡처 gross·캡처분 반영 환불)은 {@link OrderReconClient} 로 order 내부 API 에서 받고,
 * settlement 자기 합계(정산 gross·반영 환불)는 자기 settlement_db 의 {@code settlements} 에서
 * <b>생성일(created_at) 기준</b>으로 직접 집계한다. → cross-DB 연결 0.
 *
 * <p>gross 는 {@code payment_amount}, 반영 환불은 {@code refunded_amount} 를 쓴다. 둘 다 환불로
 * 소급 변동하지 않는 안정 컬럼이라 대사가 항상 수렴한다({@code net_amount} 는 환불로 실시간 감소해 부적합).
 * 상태 필터를 걸지 않는다 — CANCELED(전액 환불) 정산도 실제 캡처를 대표하므로 gross 에 포함돼야
 * order 캡처 합계(REFUNDED 포함)와 일치한다.
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
    public BigDecimal sumRefundedAgainstCaptures(LocalDate date) {
        return nz(orderReconClient.dailyTotals(date).refundedAgainstCaptures());
    }

    @Override
    public BigDecimal sumSettlementGross(LocalDate date) {
        return queryDecimal("""
                SELECT COALESCE(SUM(payment_amount), 0)
                FROM settlements
                WHERE created_at::date = ?
                """, date);
    }

    @Override
    public BigDecimal sumSettlementRefunded(LocalDate date) {
        return queryDecimal("""
                SELECT COALESCE(SUM(refunded_amount), 0)
                FROM settlements
                WHERE created_at::date = ?
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
