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

    // Business logic: Refund payment
    public void refund() {
        if (this.status != PaymentStatus.CAPTURED) {
            throw new IllegalStateException("Payment must be in CAPTURED status to refund");
        }
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = LocalDateTime.now();
    }

    // Business logic: Calculate refundable amount
    public BigDecimal getRefundableAmount() {
        return amount.subtract(refundedAmount);
    }

    // Business logic: Check if fully refunded
    public boolean isFullyRefunded() {
        return refundedAmount.compareTo(amount) >= 0;
    }

    // Business logic: Add refunded amount
    public void addRefundedAmount(BigDecimal refundAmount) {
        this.refundedAmount = this.refundedAmount.add(refundAmount);
        this.updatedAt = LocalDateTime.now();
    }


}
