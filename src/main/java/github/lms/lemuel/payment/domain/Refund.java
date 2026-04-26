package github.lms.lemuel.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Refund {

    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private RefundStatus status;
    private String reason;
    private String idempotencyKey;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Refund() {}

    public static Refund request(Long paymentId, BigDecimal amount,
                                  String idempotencyKey, String reason) {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("paymentId must be positive");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Refund r = new Refund();
        r.paymentId = paymentId;
        r.amount = amount;
        r.idempotencyKey = idempotencyKey;
        r.reason = reason;
        r.status = RefundStatus.REQUESTED;
        r.requestedAt = LocalDateTime.now();
        r.createdAt = r.requestedAt;
        r.updatedAt = r.requestedAt;
        return r;
    }

    public void markCompleted() {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException(
                "Cannot mark COMPLETED. Current status: " + this.status);
        }
        this.status = RefundStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = this.completedAt;
    }

    public void markFailed(String reason) {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException(
                "Cannot mark FAILED. Current status: " + this.status);
        }
        this.status = RefundStatus.FAILED;
        this.reason = (this.reason == null ? "" : this.reason + " | ") + "FAIL: " + reason;
        this.updatedAt = LocalDateTime.now();
    }

    // Reconstitution constructor (persistence layer only)
    public Refund(Long id, Long paymentId, BigDecimal amount, RefundStatus status,
                  String reason, String idempotencyKey,
                  LocalDateTime requestedAt, LocalDateTime completedAt,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** persistence가 INSERT 후 채운 PK를 주입할 때만 사용 */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id already assigned");
        }
        this.id = id;
    }
}
