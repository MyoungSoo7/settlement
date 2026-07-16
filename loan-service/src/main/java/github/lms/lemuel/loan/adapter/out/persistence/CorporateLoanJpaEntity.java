package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CorporateLoanStatus;
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
@Table(name = "corporate_loans")
public class CorporateLoanJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "corp_name", nullable = false)
    private String corpName;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(name = "outstanding", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstanding;

    @Column(name = "term_days", nullable = false)
    private int termDays;

    @Column(name = "credit_score", nullable = false)
    private int creditScore;

    @Column(name = "credit_grade", nullable = false, length = 1)
    private String creditGrade;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CorporateLoanStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    protected CorporateLoanJpaEntity() { }

    public CorporateLoanJpaEntity(Long id, String stockCode, String corpName, BigDecimal principal, BigDecimal fee,
                                  BigDecimal outstanding, int termDays, int creditScore, String creditGrade,
                                  CorporateLoanStatus status, LocalDateTime createdAt, Long ownerUserId) {
        this.id = id;
        this.stockCode = stockCode;
        this.corpName = corpName;
        this.principal = principal;
        this.fee = fee;
        this.outstanding = outstanding;
        this.termDays = termDays;
        this.creditScore = creditScore;
        this.creditGrade = creditGrade;
        this.status = status;
        this.createdAt = createdAt;
        this.ownerUserId = ownerUserId;
    }

    public Long getId() { return id; }
    public String getStockCode() { return stockCode; }
    public String getCorpName() { return corpName; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getOutstanding() { return outstanding; }
    public int getTermDays() { return termDays; }
    public int getCreditScore() { return creditScore; }
    public String getCreditGrade() { return creditGrade; }
    public CorporateLoanStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getOwnerUserId() { return ownerUserId; }
}
