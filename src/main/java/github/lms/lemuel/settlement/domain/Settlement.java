package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class Settlement {

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.03"); // 3% 수수료

    private Long id;
    private Long paymentId;
    private Long orderId;
    private BigDecimal paymentAmount;     // 원 결제 금액
    private BigDecimal refundedAmount;    // 환불 금액
    private BigDecimal commission;        // 수수료
    private BigDecimal netAmount;         // 실 지급액
    private SettlementStatus status;
    private LocalDate settlementDate;
    private String failureReason;         // 실패 사유
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;                 // 낙관적 락 버전 (JPA @Version)

    public Settlement() {
        this.status = SettlementStatus.REQUESTED; // 초기 상태를 REQUESTED로 변경
        this.refundedAmount = BigDecimal.ZERO;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Settlement(Long id, Long paymentId, Long orderId, BigDecimal paymentAmount,
                      BigDecimal refundedAmount, BigDecimal commission, BigDecimal netAmount,
                      SettlementStatus status, LocalDate settlementDate, String failureReason,
                      LocalDateTime confirmedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.paymentAmount = paymentAmount;
        this.refundedAmount = refundedAmount != null ? refundedAmount : BigDecimal.ZERO;
        this.commission = commission;
        this.netAmount = netAmount;
        this.status = status != null ? status : SettlementStatus.REQUESTED;
        this.settlementDate = settlementDate;
        this.failureReason = failureReason;
        this.confirmedAt = confirmedAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    public static Settlement createFromPayment(Long paymentId, Long orderId, 
                                               BigDecimal paymentAmount, LocalDate settlementDate) {
        Settlement settlement = new Settlement();
        settlement.setPaymentId(paymentId);
        settlement.setOrderId(orderId);
        settlement.setPaymentAmount(paymentAmount);
        settlement.setSettlementDate(settlementDate);
        
        settlement.validatePaymentId();
        settlement.validateAmount();
        settlement.validateSettlementDate();
        
        settlement.calculateCommissionAndNetAmount();
        
        return settlement;
    }

    private void calculateCommissionAndNetAmount() {
        this.commission = paymentAmount.multiply(COMMISSION_RATE)
                .setScale(2, RoundingMode.HALF_UP);
        this.netAmount = paymentAmount.subtract(commission)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public void validatePaymentId() {
        if (paymentId == null || paymentId <= 0) {
            throw new IllegalArgumentException("Payment ID must be a positive number");
        }
    }

    public void validateAmount() {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
    }

    public void validateSettlementDate() {
        if (settlementDate == null) {
            throw new IllegalArgumentException("Settlement date is required");
        }
    }

    // ========== 상태 머신 메서드 ==========

    /**
     * 정산 처리 시작
     * REQUESTED → PROCESSING
     */
    public void startProcessing() {
        if (this.status != SettlementStatus.REQUESTED) {
            throw new IllegalStateException(
                String.format("Cannot start processing. Current status: %s. Expected: REQUESTED", this.status)
            );
        }
        this.status = SettlementStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 정산 완료
     * PROCESSING → DONE
     */
    public void complete() {
        if (this.status != SettlementStatus.PROCESSING) {
            throw new IllegalStateException(
                String.format("Cannot complete. Current status: %s. Expected: PROCESSING", this.status)
            );
        }
        this.status = SettlementStatus.DONE;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 정산 실패
     * PROCESSING → FAILED
     */
    public void fail(String reason) {
        if (this.status != SettlementStatus.PROCESSING) {
            throw new IllegalStateException(
                String.format("Cannot fail. Current status: %s. Expected: PROCESSING", this.status)
            );
        }
        this.status = SettlementStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 재시도 (실패한 정산을 다시 요청 상태로)
     * FAILED → REQUESTED
     */
    public void retry() {
        if (this.status != SettlementStatus.FAILED) {
            throw new IllegalStateException(
                String.format("Cannot retry. Current status: %s. Expected: FAILED", this.status)
            );
        }
        this.status = SettlementStatus.REQUESTED;
        this.failureReason = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 정산 확정 — 정식 상태 머신을 우회하지 않고 REQUESTED → PROCESSING → DONE 를 한 번에 수행한다.
     * 레거시 호출부 호환을 위해 제공되며, 신규 코드는 startProcessing()/complete() 를 직접 호출할 것.
     */
    public void confirm() {
        if (this.status == SettlementStatus.REQUESTED) {
            startProcessing();
        }
        if (this.status != SettlementStatus.PROCESSING) {
            throw new IllegalStateException(
                String.format("Cannot confirm. Current status: %s. Expected: REQUESTED or PROCESSING", this.status)
            );
        }
        complete();
    }

    /**
     * 정산 취소 — 종료 상태(DONE)는 취소 불가
     */
    public void cancel() {
        if (this.status == SettlementStatus.DONE) {
            throw new IllegalStateException("DONE settlements cannot be canceled");
        }
        this.status = SettlementStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // ========== 환불 처리 ==========

    /**
     * 환불 반영 (정산 금액 조정)
     * @param refundAmount 환불 금액
     */
    public void adjustForRefund(BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }

        // DONE 상태는 이미 판매자 지급 완료된 정산 → 금액 직접 변경 금지.
        // 환불은 SettlementAdjustment 별도 레코드로만 기록해야 원장 정합성 유지됨.
        if (this.status == SettlementStatus.DONE) {
            throw new IllegalStateException(
                "DONE settlement is immutable. Use SettlementAdjustment to record the refund offset.");
        }

        if (this.refundedAmount == null) {
            this.refundedAmount = BigDecimal.ZERO;
        }

        BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(this.paymentAmount) > 0) {
            throw new IllegalArgumentException(
                "Cumulative refund " + newRefunded + " exceeds payment amount " + this.paymentAmount);
        }
        this.refundedAmount = newRefunded;

        // 순 정산 금액 재계산: (결제금액 - 환불금액 - 수수료)
        BigDecimal remainingAmount = this.paymentAmount.subtract(this.refundedAmount);
        this.netAmount = remainingAmount.subtract(this.commission).setScale(2, RoundingMode.HALF_UP);

        // 환불로 인해 정산 금액이 0 이하가 되면 취소 처리
        if (this.netAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.status = SettlementStatus.CANCELED;
        }

        this.updatedAt = LocalDateTime.now();
    }

    // ========== 상태 확인 메서드 ==========

    public boolean isConfirmed() {
        return this.status == SettlementStatus.DONE;
    }

    public boolean isPending() {
        return this.status == SettlementStatus.REQUESTED;
    }

    public boolean canRetry() {
        return this.status == SettlementStatus.FAILED;
    }

    public boolean isProcessing() {
        return this.status == SettlementStatus.PROCESSING;
    }

    public boolean isDone() {
        return this.status == SettlementStatus.DONE;
    }

    // ========== Getters and Setters ==========
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    
    public BigDecimal getPaymentAmount() { return paymentAmount; }
    public void setPaymentAmount(BigDecimal paymentAmount) { this.paymentAmount = paymentAmount; }
    
    public BigDecimal getRefundedAmount() { return refundedAmount != null ? refundedAmount : BigDecimal.ZERO; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    
    public BigDecimal getCommission() { return commission; }
    public void setCommission(BigDecimal commission) { this.commission = commission; }
    
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
    
    public SettlementStatus getStatus() { return status; }
    public void setStatus(SettlementStatus status) { this.status = status; }
    
    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
    
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
