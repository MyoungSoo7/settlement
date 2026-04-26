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
    private Long sellerId;
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

    public static Settlement createFromPayment(Long paymentId, Long orderId, Long sellerId,
                                               BigDecimal paymentAmount, BigDecimal commissionRate,
                                               LocalDate settlementDate) {
        Settlement settlement = new Settlement();
        settlement.setPaymentId(paymentId);
        settlement.setOrderId(orderId);
        settlement.setSellerId(sellerId);
        settlement.setPaymentAmount(paymentAmount);
        settlement.setSettlementDate(settlementDate);

        settlement.validatePaymentId();
        settlement.validateAmount();
        settlement.validateSettlementDate();

        CommissionCalculation calc = CommissionCalculation.calculate(paymentAmount, commissionRate);
        settlement.commission = calc.commissionAmount();
        settlement.netAmount = calc.netAmount();

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
     * 정산 취소
     */
    public void cancel() {
        if (this.status == SettlementStatus.DONE) {
            throw new IllegalStateException("DONE settlements cannot be canceled");
        }
        this.status = SettlementStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // ========== 상태 확인 메서드 ==========

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

    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }

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
}
