package github.lms.lemuel.recovery.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "seller_recoveries")
public class SellerRecoveryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_adjustment_id", nullable = false, unique = true)
    private Long sourceAdjustmentId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected SellerRecoveryJpaEntity() {
    }

    public SellerRecoveryJpaEntity(Long id, Long sourceAdjustmentId, Long sellerId,
                                   BigDecimal originalAmount, BigDecimal allocatedAmount,
                                   String status, LocalDateTime createdAt, LocalDateTime closedAt) {
        this.id = id;
        this.sourceAdjustmentId = sourceAdjustmentId;
        this.sellerId = sellerId;
        this.originalAmount = originalAmount;
        this.allocatedAmount = allocatedAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.closedAt = closedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSourceAdjustmentId() {
        return sourceAdjustmentId;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    /** 상계 진행 필드만 갱신 — 불변 필드(출처·셀러·원금)는 DB 트리거도 이중 방어한다. */
    public void applyAllocationProgress(BigDecimal allocatedAmount, String status, LocalDateTime closedAt) {
        this.allocatedAmount = allocatedAmount;
        this.status = status;
        this.closedAt = closedAt;
    }
}
