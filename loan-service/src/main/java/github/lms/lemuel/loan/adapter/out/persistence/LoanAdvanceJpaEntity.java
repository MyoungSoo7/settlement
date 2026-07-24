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

    /** 선지급일수 — 수수료·만기 계산의 단일 근거. 구 데이터는 0(DEFAULT). */
    @Column(name = "financing_days", nullable = false)
    private int financingDays;

    /** 실행 시각. 실행 전/구 데이터는 NULL. */
    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    /** 만기일(= disbursed_at + financing_days). 배치 스캐너의 자동 연체/상각 기준. 구 데이터는 NULL. */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LoanAdvanceJpaEntity() { }

    public LoanAdvanceJpaEntity(Long id, Long sellerId, BigDecimal principal, BigDecimal fee,
                                BigDecimal outstanding, LoanStatus status, int financingDays,
                                LocalDateTime disbursedAt, LocalDateTime dueAt, LocalDateTime updatedAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.principal = principal;
        this.fee = fee;
        this.outstanding = outstanding;
        this.status = status;
        this.financingDays = financingDays;
        this.disbursedAt = disbursedAt;
        this.dueAt = dueAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getOutstanding() { return outstanding; }
    public LoanStatus getStatus() { return status; }
    public int getFinancingDays() { return financingDays; }
    public LocalDateTime getDisbursedAt() { return disbursedAt; }
    public LocalDateTime getDueAt() { return dueAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
