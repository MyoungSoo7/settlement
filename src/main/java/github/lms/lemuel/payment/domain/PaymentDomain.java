package github.lms.lemuel.payment.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment Domain Entity - Pure domain model without framework dependencies
 */
@Getter
public class PaymentDomain {

    private Long id;
    private Long orderId;
    private BigDecimal amount;
    private BigDecimal refundedAmount;
    private PaymentStatus status;
    private String paymentMethod;
    private String pgTransactionId;
    private LocalDateTime capturedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructor for creating new payment
    public PaymentDomain(Long orderId, BigDecimal amount, String paymentMethod) {
        this.orderId = orderId;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.READY;
        this.refundedAmount = BigDecimal.ZERO;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Constructor for reconstitution from persistence
    public PaymentDomain(Long id, Long orderId, BigDecimal amount, BigDecimal refundedAmount,
                   PaymentStatus status, String paymentMethod, String pgTransactionId,
                   LocalDateTime capturedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.refundedAmount = refundedAmount;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.pgTransactionId = pgTransactionId;
        this.capturedAt = capturedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Business logic: Authorize payment
    public void authorize(String pgTransactionId) {
        if (this.status != PaymentStatus.READY) {
            throw new IllegalStateException("Payment must be in READY status to authorize");
        }
        this.status = PaymentStatus.AUTHORIZED;
        this.pgTransactionId = pgTransactionId;
        this.updatedAt = LocalDateTime.now();
    }

    // Business logic: Capture payment
    public void capture() {
        if (this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("Payment must be in AUTHORIZED status to capture");
        }
        this.status = PaymentStatus.CAPTURED;
        this.capturedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 부분 또는 전체 환불 요청.
     * 누적 환불액이 결제액과 같아지면 status를 REFUNDED로 전이.
     */
    public void requestRefund(java.math.BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (this.status != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                "Payment must be in CAPTURED status to refund. current=" + this.status);
        }
        java.math.BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(this.amount) > 0) {
            throw new github.lms.lemuel.common.exception.RefundExceedsPaymentException(
                String.format("Refund exceeds payment. paymentAmount=%s, alreadyRefunded=%s, requested=%s",
                    this.amount, this.refundedAmount, refundAmount));
        }
        this.refundedAmount = newRefunded;
        if (this.refundedAmount.compareTo(this.amount) == 0) {
            this.status = PaymentStatus.REFUNDED;
        }
        this.updatedAt = java.time.LocalDateTime.now();
    }

    // Business logic: Calculate refundable amount
    public BigDecimal getRefundableAmount() {
        return amount.subtract(refundedAmount);
    }

    // Business logic: Check if fully refunded
    public boolean isFullyRefunded() {
        return refundedAmount.compareTo(amount) >= 0;
    }


}
