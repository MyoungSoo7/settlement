package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.LoanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "loan_advances")
public class LoanAdvanceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(name = "outstanding", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstanding;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LoanStatus status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LoanAdvanceJpaEntity() { }

    public LoanAdvanceJpaEntity(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                                BigDecimal outstanding, LoanStatus status, LocalDateTime updatedAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.principal = principal;
        this.fee = fee;
        this.outstanding = outstanding;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getOutstanding() { return outstanding; }
    public LoanStatus getStatus() { return status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
