package github.lms.lemuel.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * order-service 가 <b>자신이 소유한 opslab</b> 의 결제·환불·아웃박스에서 대사용 합계/행을 산출한다.
 *
 * <p>ADR 0020 Phase 5(self-totals 대사): settlement 가 order DB 를 직접 읽던 cross-DB 대사를
 * 제거하기 위해, order 가 자기 숫자를 {@link InternalReconController} 로 노출한다. settlement 는
 * 자기 settlement_db 숫자와 이 값을 비교한다 — 양측 모두 자기 DB 만 읽으므로 cross-DB 연결 0.
 *
 * <p>모든 쿼리는 order 소유 테이블(opslab.payments/refunds/outbox_events)만 조회 → MSA 경계 부합.
 */
@Repository
public class ReconQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReconQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 해당 날짜 캡처된 결제 gross amount 합계 — <b>캡처 이력 기준</b> (이후 환불돼 REFUNDED 로
     * 전이한 건도 포함). 현재 상태 CAPTURED 만 세면 환불 시점에 과거 날짜의 합계가 소급 변동해
     * 대사 기준값으로 쓸 수 없다 (환불은 별도 축으로 대조).
     */
    public BigDecimal sumCapturedPayments(LocalDate date) {
        return decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM opslab.payments
                WHERE status IN ('CAPTURED', 'REFUNDED') AND captured_at::date = ?
                """, date);
    }

    /** 기간 내 캡처된 결제 gross amount 합계 (캡처 이력 기준 — 위 단일 날짜 버전과 동일 의미). */
    public BigDecimal sumCapturedPayments(LocalDate from, LocalDate to) {
        return decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM opslab.payments
                WHERE status IN ('CAPTURED', 'REFUNDED') AND captured_at::date BETWEEN ? AND ?
                """, from, to);
    }

    /**
     * 해당 날짜 캡처된 결제에 대해 반영된 환불액 합계 — <b>캡처일 기준</b>.
     * settlement 는 자기 정산의 {@code refunded_amount}(생성일=캡처일)를 이 값과 대조해
     * "order 는 환불했는데 정산엔 미반영" 을 감지한다. 환불 완료일이 아니라 캡처일로 키를 맞춰
     * 처리 지연(T+N·백필)에 흔들리지 않는 안정 축을 만든다.
     */
    public BigDecimal sumRefundedAgainstCaptures(LocalDate date) {
        return decimal("""
                SELECT COALESCE(SUM(refunded_amount), 0) FROM opslab.payments
                WHERE status IN ('CAPTURED', 'REFUNDED') AND captured_at::date = ?
                """, date);
    }

    /** 해당 날짜 COMPLETED 환불 amount 합계. */
    public BigDecimal sumCompletedRefunds(LocalDate date) {
        return decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM opslab.refunds
                WHERE status = 'COMPLETED' AND completed_at::date = ?
                """, date);
    }

    /** 기간 내 COMPLETED 환불 amount 합계. */
    public BigDecimal sumCompletedRefunds(LocalDate from, LocalDate to) {
        return decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM opslab.refunds
                WHERE status = 'COMPLETED' AND completed_at::date BETWEEN ? AND ?
                """, from, to);
    }

    /**
     * 주어진 refund id 중 COMPLETED 인 것의 amount 합계.
     * settlement 의 settlement_adjustments(자기 DB)가 참조하는 refund_id 집합을 받아,
     * cross-DB JOIN 없이 "조정에 연결된 환불 합계"를 산출한다.
     */
    public BigDecimal sumCompletedRefundsByIds(List<Long> refundIds) {
        if (refundIds == null || refundIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String placeholders = String.join(",", refundIds.stream().map(id -> "?").toList());
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM opslab.refunds"
                + " WHERE status = 'COMPLETED' AND id IN (" + placeholders + ")";
        BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, refundIds.toArray());
        return result != null ? result : BigDecimal.ZERO;
    }

    /**
     * 해당 날짜 캡처된 결제 <b>건수</b> — 캡처 이력 기준 (INV-9 건수 대사 축).
     * 금액 합계 대사는 +N/−N 상쇄 오류를 통과시키므로 건수를 병행 대조한다.
     */
    public long countCapturedPayments(LocalDate date) {
        Long result = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM opslab.payments
                WHERE status IN ('CAPTURED', 'REFUNDED') AND captured_at::date = ?
                """, Long.class, date);
        return result != null ? result : 0L;
    }

    /** 해당 날짜 COMPLETED 환불 건수 (완료일 기준). */
    public long countCompletedRefunds(LocalDate date) {
        Long result = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM opslab.refunds
                WHERE status = 'COMPLETED' AND completed_at::date = ?
                """, Long.class, date);
        return result != null ? result : 0L;
    }

    /**
     * 기간 내 COMPLETED 환불 행 목록 (완료일 기준) — INV-8 지연 환불 조정 대사용.
     * settlement 가 자기 settlement_adjustments.refund_id 집합과 대조해
     * "환불은 완료됐는데 조정(역정산)이 없는 건"을 찾는다.
     */
    public List<CompletedRefundRow> listCompletedRefunds(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query("""
                SELECT id, payment_id, amount, completed_at::date AS completed_date
                  FROM opslab.refunds
                 WHERE status = 'COMPLETED' AND completed_at::date BETWEEN ? AND ?
                 ORDER BY id
                 LIMIT ?
                """, (rs, n) -> new CompletedRefundRow(
                        rs.getLong("id"),
                        rs.getLong("payment_id"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("completed_date", LocalDate.class)),
                from, to, limit);
    }

    /** 기간 내 PaymentCaptured outbox 이벤트가 PUBLISHED 로 전이된 건수. */
    public long countPaymentCapturedPublished(LocalDate from, LocalDate to) {
        Long result = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM opslab.outbox_events
                WHERE event_type = 'PaymentCaptured' AND status = 'PUBLISHED'
                  AND published_at::date BETWEEN ? AND ?
                """, Long.class, from, to);
        return result != null ? result : 0L;
    }

    /** 해당 영업일 CAPTURED/REFUNDED 결제 행 (PG 거래키 보유분) — PG 파일 대사용. */
    public List<ReconPaymentRow> loadCapturedPaymentRows(LocalDate date) {
        return jdbcTemplate.query("""
                SELECT id, pg_transaction_id, amount, refunded_amount, captured_at::date AS captured_date
                  FROM opslab.payments
                 WHERE pg_transaction_id IS NOT NULL
                   AND status IN ('CAPTURED', 'REFUNDED')
                   AND captured_at::date = ?
                """, (rs, n) -> new ReconPaymentRow(
                        rs.getLong("id"),
                        rs.getString("pg_transaction_id"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("refunded_amount"),
                        rs.getObject("captured_date", LocalDate.class)),
                date);
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal result = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return result != null ? result : BigDecimal.ZERO;
    }

    /** PG 대사용 결제 행 DTO (order → settlement 직렬화). */
    public record ReconPaymentRow(Long paymentId, String pgTransactionId, BigDecimal amount,
                                  BigDecimal refundedAmount, LocalDate capturedDate) {
    }

    /** INV-8 지연 환불 대사용 완료 환불 행 DTO (order → settlement 직렬화). */
    public record CompletedRefundRow(Long refundId, Long paymentId, BigDecimal amount,
                                     LocalDate completedDate) {
    }
}
