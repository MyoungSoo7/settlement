package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.domain.PayoutStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payouts")
public class PayoutJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    @Column(name = "bank_account_number", nullable = false, length = 50)
    private String bankAccountNumber;

    @Column(name = "account_holder_name", nullable = false, length = 100)
    private String accountHolderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(name = "firm_banking_transaction_id", length = 100)
    private String firmBankingTransactionId;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "operator_id", length = 100)
    private String operatorId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PayoutJpaEntity() { }

    public PayoutJpaEntity(Long id, Long settlementId, Long sellerId, BigDecimal amount,
                            String bankCode, String bankAccountNumber, String accountHolderName,
                            PayoutStatus status, String firmBankingTransactionId, String failureReason,
                            int retryCount, String operatorId,
                            LocalDateTime requestedAt, LocalDateTime sentAt,
                            LocalDateTime completedAt, LocalDateTime failedAt,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.bankCode = bankCode;
        this.bankAccountNumber = bankAccountNumber;
        this.accountHolderName = accountHolderName;
        this.status = status;
        this.firmBankingTransactionId = firmBankingTransactionId;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.operatorId = operatorId;
        this.requestedAt = requestedAt;
        this.sentAt = sentAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public String getBankCode() { return bankCode; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public String getAccountHolderName() { return accountHolderName; }
    public PayoutStatus getStatus() { return status; }
    public String getFirmBankingTransactionId() { return firmBankingTransactionId; }
    public String getFailureReason() { return failureReason; }
    public int getRetryCount() { return retryCount; }
    public String getOperatorId() { return operatorId; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void applyDomainState(PayoutStatus status, String firmBankingTransactionId,
                                  String failureReason, int retryCount, String operatorId,
                                  LocalDateTime sentAt, LocalDateTime completedAt,
                                  LocalDateTime failedAt, LocalDateTime updatedAt) {
        this.status = status;
        this.firmBankingTransactionId = firmBankingTransactionId;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.operatorId = operatorId;
        this.sentAt = sentAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.updatedAt = updatedAt;
    }
}
