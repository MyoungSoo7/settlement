package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 내부 payments + refunds 원장에서 PG 매칭용으로 추출한 한 줄.
 *
 * <p>대사 비교를 위한 read-only projection. payments.pg_transaction_id 를 키로
 * 같은 거래의 PG 측 데이터와 1:1 매칭한다.
 */
public record InternalPaymentRow(
        Long paymentId,
        String pgTransactionId,
        BigDecimal capturedAmount,    // payments.amount (CAPTURED 시점)
        BigDecimal refundedAmount,    // payments.refunded_amount 누적
        LocalDate capturedDate
) {
    public InternalPaymentRow {
        if (paymentId == null) throw new PgReconciliationInvariantViolationException("paymentId 는 필수입니다");
        if (pgTransactionId == null) throw new PgReconciliationInvariantViolationException("pgTransactionId 는 필수입니다");
        if (capturedAmount == null) throw new PgReconciliationInvariantViolationException("capturedAmount 는 필수입니다");
        if (refundedAmount == null) refundedAmount = BigDecimal.ZERO;
    }

    /**
     * 내부 순매출. PG 의 netAmount 와 1:1 비교.
     */
    public BigDecimal netAmount() {
        return capturedAmount.subtract(refundedAmount);
    }
}
