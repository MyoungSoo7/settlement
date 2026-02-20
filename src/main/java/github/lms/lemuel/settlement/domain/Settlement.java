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
    private BigDecimal commission;        // 수수료
    private BigDecimal netAmount;         // 실 지급액
    private SettlementStatus status;
    private LocalDate settlementDate;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Settlement() {
        this.status = SettlementStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Settlement(Long id, Long paymentId, Long orderId, BigDecimal paymentAmount,
                      BigDecimal commission, BigDecimal netAmount,
                      SettlementStatus status, LocalDate settlementDate, LocalDateTime confirmedAt,
                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.paymentAmount = paymentAmount;
        this.commission = commission;
        this.netAmount = netAmount;
        this.status = status != null ? status : SettlementStatus.PENDING;
        this.settlementDate = settlementDate;
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

    public void confirm() {
        if (this.status != SettlementStatus.PENDING && this.status != SettlementStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("Only PENDING or WAITING_APPROVAL settlements can be confirmed");
        }
        this.status = SettlementStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status == SettlementStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED settlements cannot be canceled");
        }
        this.status = SettlementStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isConfirmed() {
        return this.status == SettlementStatus.CONFIRMED;
    }

    public boolean isPending() {
        return this.status == SettlementStatus.PENDING || this.status == SettlementStatus.WAITING_APPROVAL;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public BigDecimal getPaymentAmount() { return paymentAmount; }
    public void setPaymentAmount(BigDecimal paymentAmount) { this.paymentAmount = paymentAmount; }
    public BigDecimal getCommission() { return commission; }
    public void setCommission(BigDecimal commission) { this.commission = commission; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
    public SettlementStatus getStatus() { return status; }
    public void setStatus(SettlementStatus status) { this.status = status; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
