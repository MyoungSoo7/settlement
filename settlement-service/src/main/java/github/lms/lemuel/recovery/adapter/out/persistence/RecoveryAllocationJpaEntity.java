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
@Table(name = "recovery_allocations")
public class RecoveryAllocationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recovery_id", nullable = false)
    private Long recoveryId;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RecoveryAllocationJpaEntity() {
    }

    public RecoveryAllocationJpaEntity(Long id, Long recoveryId, Long settlementId,
                                       BigDecimal amount, LocalDateTime createdAt) {
        this.id = id;
        this.recoveryId = recoveryId;
        this.settlementId = settlementId;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRecoveryId() {
        return recoveryId;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
