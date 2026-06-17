package github.lms.lemuel.settlement.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 정산건별 선정산 대출 차감액. settlement_id PK 로 LoanRepaymentApplied 중복 수신에도 멱등.
 */
@Entity
@Table(name = "settlement_loan_deductions")
public class SettlementLoanDeductionJpaEntity {

    @Id
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "deducted", nullable = false, precision = 19, scale = 2)
    private BigDecimal deducted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected SettlementLoanDeductionJpaEntity() { }

    public SettlementLoanDeductionJpaEntity(Long settlementId, Long sellerId, BigDecimal deducted) {
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.deducted = deducted;
        this.createdAt = LocalDateTime.now();
    }

    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getDeducted() { return deducted; }
}
