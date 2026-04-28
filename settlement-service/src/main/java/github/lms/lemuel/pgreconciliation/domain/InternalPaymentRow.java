package github.lms.lemuel.pgreconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

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
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(pgTransactionId, "pgTransactionId");
        Objects.requireNonNull(capturedAmount, "capturedAmount");
        if (refundedAmount == null) refundedAmount = BigDecimal.ZERO;
    }

    /**
     * 내부 순매출. PG 의 netAmount 와 1:1 비교.
     */
    public BigDecimal netAmount() {
        return capturedAmount.subtract(refundedAmount);
    }
}
