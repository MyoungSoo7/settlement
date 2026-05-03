package github.lms.lemuel.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 환불 도메인 — V4 refunds 테이블의 도메인 모델.
 * idempotency_key 를 이용한 멱등성 보장 및 환불 이력 추적의 단위.
 */
public class Refund {

    public enum Status { REQUESTED, COMPLETED, FAILED }

    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private Status status;
    private String reason;
    private String idempotencyKey;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Refund() {}

    public static Refund request(Long paymentId, BigDecimal amount, String idempotencyKey, String reason) {
        if (paymentId == null) throw new IllegalArgumentException("paymentId required");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey required");
        }
        Refund refund = new Refund();
        refund.paymentId = paymentId;
        refund.amount = amount;
        refund.idempotencyKey = idempotencyKey;
        refund.reason = reason;
        refund.status = Status.REQUESTED;
        refund.requestedAt = LocalDateTime.now();
        refund.createdAt = LocalDateTime.now();
        refund.updatedAt = LocalDateTime.now();
        return refund;
    }

    public void markCompleted() {
        if (this.status != Status.REQUESTED) {
            throw new IllegalStateException("Only REQUESTED refunds can be completed. current=" + status);
        }
        this.status = Status.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String failureReason) {
        if (this.status != Status.REQUESTED) {
            throw new IllegalStateException("Only REQUESTED refunds can fail. current=" + status);
        }
        this.status = Status.FAILED;
        this.reason = failureReason;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isCompleted() { return status == Status.COMPLETED; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
