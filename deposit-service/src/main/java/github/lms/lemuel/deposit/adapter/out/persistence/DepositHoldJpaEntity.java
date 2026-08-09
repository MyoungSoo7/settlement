package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "deposit_holds",
    schema = "opslab",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_deposit_holds_holder",
        columnNames = {"holder_type", "holder_reference"}
    )
)
@EntityListeners(AuditingEntityListener.class)
public class DepositHoldJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "holder_type", nullable = false, length = 30)
    private DepositHolderType holderType;

    @Column(name = "holder_reference", nullable = false, length = 100)
    private String holderReference;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositHoldStatus status;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DepositHoldJpaEntity() {}

    public DepositHoldJpaEntity(Long accountId, DepositHolderType holderType,
                                 String holderReference, BigDecimal originalAmount,
                                 BigDecimal remainingAmount, DepositHoldStatus status,
                                 LocalDateTime expiresAt) {
        this.accountId = accountId;
        this.holderType = holderType;
        this.holderReference = holderReference;
        this.originalAmount = originalAmount;
        this.remainingAmount = remainingAmount;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public DepositHolderType getHolderType() { return holderType; }
    public String getHolderReference() { return holderReference; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public DepositHoldStatus getStatus() { return status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setRemainingAmount(BigDecimal remainingAmount) { this.remainingAmount = remainingAmount; }
    public void setStatus(DepositHoldStatus status) { this.status = status; }
}
