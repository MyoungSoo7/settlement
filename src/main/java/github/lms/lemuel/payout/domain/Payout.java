package github.lms.lemuel.payout.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Payout {

    private Long id;
    private Long settlementId;
    private Long sellerId;
    private BigDecimal amount;
    private PayoutStatus status;
    private String bankTransactionId;
    private String failureReason;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Payout() {}

    public static Payout request(Long settlementId, Long sellerId, BigDecimal amount) {
        if (settlementId == null) throw new IllegalArgumentException("settlementId required");
        if (sellerId == null) throw new IllegalArgumentException("sellerId required");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Payout p = new Payout();
        p.settlementId = settlementId;
        p.sellerId = sellerId;
        p.amount = amount;
        p.status = PayoutStatus.PENDING;
        p.requestedAt = LocalDateTime.now();
        p.createdAt = p.requestedAt;
        p.updatedAt = p.requestedAt;
        return p;
    }

    public void markSucceeded(String bankTransactionId) {
        if (this.status != PayoutStatus.PENDING) {
            throw new IllegalStateException("Cannot mark SUCCEEDED. status=" + this.status);
        }
        this.status = PayoutStatus.SUCCEEDED;
        this.bankTransactionId = bankTransactionId;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = this.completedAt;
    }

    public void markFailed(String reason) {
        if (this.status != PayoutStatus.PENDING) {
            throw new IllegalStateException("Cannot mark FAILED. status=" + this.status);
        }
        this.status = PayoutStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    public void retry() {
        if (this.status != PayoutStatus.FAILED) {
            throw new IllegalStateException("Can only retry FAILED. status=" + this.status);
        }
        this.status = PayoutStatus.PENDING;
        this.failureReason = null;
        this.updatedAt = LocalDateTime.now();
    }

    // Reconstitution constructor (persistence layer only)
    public Payout(Long id, Long settlementId, Long sellerId, BigDecimal amount,
                  PayoutStatus status, String bankTransactionId, String failureReason,
                  LocalDateTime requestedAt, LocalDateTime completedAt,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.status = status;
        this.bankTransactionId = bankTransactionId;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** persistence가 INSERT 후 채운 PK를 주입할 때만 사용 */
    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id already assigned");
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public PayoutStatus getStatus() { return status; }
    public String getBankTransactionId() { return bankTransactionId; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
