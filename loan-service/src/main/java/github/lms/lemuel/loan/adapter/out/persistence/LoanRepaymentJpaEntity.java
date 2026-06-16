package github.lms.lemuel.loan.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 정산 확정 시 차감 상환 기록. settlement_id UNIQUE 로 정산건당 1회 차감(멱등 최종 방어).
 */
@Entity
@Table(name = "loan_repayments")
public class LoanRepaymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "deducted", nullable = false, precision = 19, scale = 2)
    private BigDecimal deducted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected LoanRepaymentJpaEntity() { }

    public LoanRepaymentJpaEntity(Long settlementId, Long sellerId, BigDecimal deducted) {
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.deducted = deducted;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getDeducted() { return deducted; }
}
