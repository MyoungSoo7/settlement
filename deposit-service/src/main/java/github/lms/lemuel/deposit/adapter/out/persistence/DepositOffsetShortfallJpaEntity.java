package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositHolderType;
import github.lms.lemuel.deposit.domain.DepositShortfallStatus;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_offset_shortfalls", schema = "opslab")
@EntityListeners(AuditingEntityListener.class)
public class DepositOffsetShortfallJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "holder_type", nullable = false, length = 30)
    private DepositHolderType holderType;

    @Column(name = "holder_reference", nullable = false, length = 100)
    private String holderReference;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "applied_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal appliedAmount;

    @Column(name = "shortfall_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal shortfallAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositShortfallStatus status;

    @Column(name = "source_hold_id")
    private Long sourceHoldId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected DepositOffsetShortfallJpaEntity() {}

    public DepositOffsetShortfallJpaEntity(Long sellerId, DepositHolderType holderType,
                                            String holderReference, BigDecimal requestedAmount,
                                            BigDecimal appliedAmount, BigDecimal shortfallAmount,
                                            DepositShortfallStatus status, Long sourceHoldId,
                                            OffsetDateTime occurredAt) {
        this.sellerId = sellerId;
        this.holderType = holderType;
        this.holderReference = holderReference;
        this.requestedAmount = requestedAmount;
        this.appliedAmount = appliedAmount;
        this.shortfallAmount = shortfallAmount;
        this.status = status;
        this.sourceHoldId = sourceHoldId;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public DepositHolderType getHolderType() { return holderType; }
    public String getHolderReference() { return holderReference; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public BigDecimal getAppliedAmount() { return appliedAmount; }
    public BigDecimal getShortfallAmount() { return shortfallAmount; }
    public DepositShortfallStatus getStatus() { return status; }
    public Long getSourceHoldId() { return sourceHoldId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setAppliedAmount(BigDecimal appliedAmount) { this.appliedAmount = appliedAmount; }
    public void setShortfallAmount(BigDecimal shortfallAmount) { this.shortfallAmount = shortfallAmount; }
    public void setStatus(DepositShortfallStatus status) { this.status = status; }
}
