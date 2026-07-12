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
    private Long chargebackId;         // V44 — 카드사 분쟁 연결. refund_id 와 양립 (둘 중 하나만)
    private Long reconciliationDiscrepancyId; // PG 대사 승인 clawback 연결. refund_id/chargeback_id 와 배타 (다중 출처 금지)
    private BigDecimal amount;         // 항상 음수 (환불·분쟁·대사 반영분)
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

    /**
     * 카드사 분쟁(Chargeback) ACCEPTED 시 정산에서 차감하는 음수 row.
     * V44 chk_adjustment_refund_xor_chargeback 제약과 일치 — chargebackId 만 채우고 refundId 는 NULL.
     */
    public static SettlementAdjustment ofChargeback(Long settlementId, Long chargebackId,
                                                     BigDecimal chargebackAmount,
                                                     LocalDate adjustmentDate) {
        if (chargebackId == null || chargebackId <= 0) {
            throw new IllegalArgumentException("chargebackId 필수");
        }
        if (chargebackAmount == null || chargebackAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Chargeback amount must be greater than zero");
        }
        SettlementAdjustment adjustment = new SettlementAdjustment();
        adjustment.settlementId = settlementId;
        adjustment.chargebackId = chargebackId;
        adjustment.amount = chargebackAmount.negate();
        adjustment.status = "PENDING";
        adjustment.adjustmentDate = adjustmentDate;
        adjustment.createdAt = LocalDateTime.now();
        return adjustment;
    }

    /**
     * PG 대사 차이(Discrepancy) 승인 → 정산에서 회수(clawback)하는 음수 row.
     * refund_id/chargeback_id 는 NULL, reconciliationDiscrepancyId 만 채워 3-way 다중출처금지 제약과 일치.
     */
    public static SettlementAdjustment ofReconciliation(Long settlementId, Long discrepancyId,
                                                        BigDecimal clawbackAmount,
                                                        LocalDate adjustmentDate) {
        if (discrepancyId == null || discrepancyId <= 0) {
            throw new IllegalArgumentException("discrepancyId 필수");
        }
        if (clawbackAmount == null || clawbackAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Reconciliation clawback amount must be greater than zero");
        }
        SettlementAdjustment adjustment = new SettlementAdjustment();
        adjustment.settlementId = settlementId;
        adjustment.reconciliationDiscrepancyId = discrepancyId;
        adjustment.amount = clawbackAmount.negate();   // 감사 규약: 음수 기록
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

    public Long getChargebackId() { return chargebackId; }
    public void setChargebackId(Long chargebackId) { this.chargebackId = chargebackId; }

    public Long getReconciliationDiscrepancyId() { return reconciliationDiscrepancyId; }
    public void setReconciliationDiscrepancyId(Long reconciliationDiscrepancyId) {
        this.reconciliationDiscrepancyId = reconciliationDiscrepancyId;
    }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getAdjustmentDate() { return adjustmentDate; }
    public void setAdjustmentDate(LocalDate adjustmentDate) { this.adjustmentDate = adjustmentDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
