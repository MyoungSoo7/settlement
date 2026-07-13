package github.lms.lemuel.settlement.domain;

import github.lms.lemuel.settlement.domain.exception.NegativeAdjustmentAmountException;
import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 조정(역정산) — 환불 발생 시 원 정산을 직접 수정하지 않고
 * 별도의 음수 금액 레코드로 남겨 감사 추적을 보존한다.
 *
 * <p>불변식:
 * <ul>
 *   <li>{@code amount} 는 항상 음수 (환불·분쟁·대사 반영분) — 생성자에서 강제한다.</li>
 *   <li>refundId / chargebackId / reconciliationDiscrepancyId 는 한 row 당 최대 하나만 채워진다
 *       (V44·V20260712 배타 제약과 일치). 각 {@code of*} 팩토리가 정확히 하나만 세팅한다.</li>
 * </ul>
 *
 * <p>객체는 append-only 감사 레코드라 생성 후 상태를 바꾸지 않는다. 영속 레코드 복원은
 * {@link #rehydrate} 팩토리로만 수행하며 public setter 는 두지 않는다.
 */
public class SettlementAdjustment {

    private final Long id;
    private final Long settlementId;
    private final Long refundId;             // Refund 엔티티 도입 전까지 nullable 허용
    private final Long chargebackId;         // V44 — 카드사 분쟁 연결. refund_id 와 양립 (둘 중 하나만)
    private final Long reconciliationDiscrepancyId; // PG 대사 승인 clawback 연결. refund_id/chargeback_id 와 배타 (다중 출처 금지)
    private final BigDecimal amount;         // 항상 음수 (환불·분쟁·대사 반영분)
    private final SettlementAdjustmentStatus status;
    private final LocalDate adjustmentDate;
    private final LocalDateTime createdAt;

    private SettlementAdjustment(Long id, Long settlementId, Long refundId, Long chargebackId,
                                 Long reconciliationDiscrepancyId, BigDecimal amount,
                                 SettlementAdjustmentStatus status, LocalDate adjustmentDate,
                                 LocalDateTime createdAt) {
        if (amount == null || amount.signum() >= 0) {
            throw new NegativeAdjustmentAmountException(amount);
        }
        this.id = id;
        this.settlementId = settlementId;
        this.refundId = refundId;
        this.chargebackId = chargebackId;
        this.reconciliationDiscrepancyId = reconciliationDiscrepancyId;
        this.amount = amount;
        this.status = status != null ? status : SettlementAdjustmentStatus.PENDING;
        this.adjustmentDate = adjustmentDate;
        this.createdAt = createdAt;
    }

    public static SettlementAdjustment ofRefund(Long settlementId, Long refundId,
                                                BigDecimal refundAmount, LocalDate adjustmentDate) {
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SettlementInvariantViolationException("Refund amount must be greater than zero");
        }
        return new SettlementAdjustment(null, settlementId, refundId, null, null,
                refundAmount.negate(),                          // 감사 규약: 음수 기록
                SettlementAdjustmentStatus.PENDING, adjustmentDate, LocalDateTime.now());
    }

    /**
     * 카드사 분쟁(Chargeback) ACCEPTED 시 정산에서 차감하는 음수 row.
     * V44 chk_adjustment_refund_xor_chargeback 제약과 일치 — chargebackId 만 채우고 refundId 는 NULL.
     */
    public static SettlementAdjustment ofChargeback(Long settlementId, Long chargebackId,
                                                     BigDecimal chargebackAmount,
                                                     LocalDate adjustmentDate) {
        if (chargebackId == null || chargebackId <= 0) {
            throw new SettlementInvariantViolationException("chargebackId 필수");
        }
        if (chargebackAmount == null || chargebackAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SettlementInvariantViolationException("Chargeback amount must be greater than zero");
        }
        return new SettlementAdjustment(null, settlementId, null, chargebackId, null,
                chargebackAmount.negate(),
                SettlementAdjustmentStatus.PENDING, adjustmentDate, LocalDateTime.now());
    }

    /**
     * PG 대사 차이(Discrepancy) 승인 → 정산에서 회수(clawback)하는 음수 row.
     * refund_id/chargeback_id 는 NULL, reconciliationDiscrepancyId 만 채워 3-way 다중출처금지 제약과 일치.
     */
    public static SettlementAdjustment ofReconciliation(Long settlementId, Long discrepancyId,
                                                        BigDecimal clawbackAmount,
                                                        LocalDate adjustmentDate) {
        if (discrepancyId == null || discrepancyId <= 0) {
            throw new SettlementInvariantViolationException("discrepancyId 필수");
        }
        if (clawbackAmount == null || clawbackAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SettlementInvariantViolationException("Reconciliation clawback amount must be greater than zero");
        }
        return new SettlementAdjustment(null, settlementId, null, null, discrepancyId,
                clawbackAmount.negate(),                        // 감사 규약: 음수 기록
                SettlementAdjustmentStatus.PENDING, adjustmentDate, LocalDateTime.now());
    }

    /**
     * 영속 레코드 복원 전용(어댑터의 toDomain 에서만 호출). 저장된 필드를 그대로 재구성한다.
     */
    public static SettlementAdjustment rehydrate(Long id, Long settlementId, Long refundId,
                                                 Long chargebackId, Long reconciliationDiscrepancyId,
                                                 BigDecimal amount, SettlementAdjustmentStatus status,
                                                 LocalDate adjustmentDate, LocalDateTime createdAt) {
        return new SettlementAdjustment(id, settlementId, refundId, chargebackId,
                reconciliationDiscrepancyId, amount, status, adjustmentDate, createdAt);
    }

    public Long getId() { return id; }

    public Long getSettlementId() { return settlementId; }

    public Long getRefundId() { return refundId; }

    public Long getChargebackId() { return chargebackId; }

    public Long getReconciliationDiscrepancyId() { return reconciliationDiscrepancyId; }

    public BigDecimal getAmount() { return amount; }

    public SettlementAdjustmentStatus getStatus() { return status; }

    public LocalDate getAdjustmentDate() { return adjustmentDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
