package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 조정(역정산) — 환불 발생 시 원 정산을 직접 수정하지 않고
 * 별도의 음수 금액 레코드로 남겨 감사 추적을 보존한다.
 */
public class SettlementAdjustment {

    private Long id;
    private Long settlementId;
    private Long refundId;             // Refund 엔티티 도입 전까지 nullable 허용
    private BigDecimal amount;         // 항상 음수 (환불 반영분)
    private String status;
    private LocalDate adjustmentDate;
    private LocalDateTime createdAt;

    public SettlementAdjustment() {}

    public static SettlementAdjustment ofRefund(Long settlementId, BigDecimal refundAmount, LocalDate adjustmentDate) {
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }
        SettlementAdjustment adjustment = new SettlementAdjustment();
        adjustment.settlementId = settlementId;
        adjustment.amount = refundAmount.negate();     // 감사 규약: 음수 기록
        adjustment.status = "PENDING";
        adjustment.adjustmentDate = adjustmentDate;
        adjustment.createdAt = LocalDateTime.now();
        return adjustment;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }

    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getAdjustmentDate() { return adjustmentDate; }
    public void setAdjustmentDate(LocalDate adjustmentDate) { this.adjustmentDate = adjustmentDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
