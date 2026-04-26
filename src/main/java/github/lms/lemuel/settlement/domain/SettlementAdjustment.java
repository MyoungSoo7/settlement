package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class SettlementAdjustment {

    private Long id;
    private Long settlementId;
    private Long refundId;
    private BigDecimal amount;            // 양수로 보관 (DB는 음수로 저장)
    private SettlementAdjustmentStatus status;
    private LocalDate adjustmentDate;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private SettlementAdjustment() {}

    public static SettlementAdjustment forRefund(Long settlementId, Long refundId,
                                                  BigDecimal amount, LocalDate adjustmentDate) {
        if (settlementId == null || refundId == null || adjustmentDate == null) {
            throw new IllegalArgumentException("settlementId/refundId/adjustmentDate required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        SettlementAdjustment adj = new SettlementAdjustment();
        adj.settlementId = settlementId;
        adj.refundId = refundId;
        adj.amount = amount;
        adj.status = SettlementAdjustmentStatus.PENDING;
        adj.adjustmentDate = adjustmentDate;
        adj.createdAt = LocalDateTime.now();
        adj.updatedAt = adj.createdAt;
        return adj;
    }

    public void confirm() {
        if (this.status != SettlementAdjustmentStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm. status=" + this.status);
        }
        this.status = SettlementAdjustmentStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = this.confirmedAt;
    }

    // Reconstitution
    public SettlementAdjustment(Long id, Long settlementId, Long refundId,
                                 BigDecimal amount, SettlementAdjustmentStatus status,
                                 LocalDate adjustmentDate, LocalDateTime confirmedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.refundId = refundId;
        this.amount = amount;
        this.status = status;
        this.adjustmentDate = adjustmentDate;
        this.confirmedAt = confirmedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id already assigned");
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getRefundId() { return refundId; }
    public BigDecimal getAmount() { return amount; }
    public SettlementAdjustmentStatus getStatus() { return status; }
    public LocalDate getAdjustmentDate() { return adjustmentDate; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
