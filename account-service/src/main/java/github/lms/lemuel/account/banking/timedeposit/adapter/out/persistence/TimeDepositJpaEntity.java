package github.lms.lemuel.account.banking.timedeposit.adapter.out.persistence;

import github.lms.lemuel.account.banking.timedeposit.domain.Compounding;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDepositStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 정기예금 계좌 영속 엔티티 ({@code time_deposits}).
 *
 * <p>{@code @Table} 에 schema 를 적지 않는다 — Hibernate {@code default_schema=opslab} 이 붙인다.
 * 여기에 하드코딩하면 스키마를 바꿀 때 코드가 따라와야 한다.
 *
 * <p>이율은 {@code numeric(9,6)} — 연 0.031500 같은 6자리 소수를 무손실로 담는다(bp 미만까지).
 * 금액은 GL 과 동일한 {@code numeric(19,2)}.
 */
@Entity
@Table(name = "time_deposits")
public class TimeDepositJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "depositor_id", nullable = false, length = 64)
    private String depositorId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "annual_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal annualRate;

    @Column(name = "early_termination_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal earlyTerminationRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "compounding", nullable = false, length = 20)
    private Compounding compounding;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TimeDepositStatus status;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "settled_interest", precision = 19, scale = 2)
    private BigDecimal settledInterest;

    @Column(name = "payout_amount", precision = 19, scale = 2)
    private BigDecimal payoutAmount;

    protected TimeDepositJpaEntity() { }

    public TimeDepositJpaEntity(Long id, String depositorId, String productName, BigDecimal principal,
                                BigDecimal annualRate, BigDecimal earlyTerminationRate, Compounding compounding,
                                int termMonths, LocalDate openedOn, LocalDate maturityDate,
                                TimeDepositStatus status, LocalDate closedOn,
                                BigDecimal settledInterest, BigDecimal payoutAmount) {
        this.id = id;
        this.depositorId = depositorId;
        this.productName = productName;
        this.principal = principal;
        this.annualRate = annualRate;
        this.earlyTerminationRate = earlyTerminationRate;
        this.compounding = compounding;
        this.termMonths = termMonths;
        this.openedOn = openedOn;
        this.maturityDate = maturityDate;
        this.status = status;
        this.closedOn = closedOn;
        this.settledInterest = settledInterest;
        this.payoutAmount = payoutAmount;
    }

    public Long getId() { return id; }
    public String getDepositorId() { return depositorId; }
    public String getProductName() { return productName; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public BigDecimal getEarlyTerminationRate() { return earlyTerminationRate; }
    public Compounding getCompounding() { return compounding; }
    public int getTermMonths() { return termMonths; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public TimeDepositStatus getStatus() { return status; }
    public LocalDate getClosedOn() { return closedOn; }
    public BigDecimal getSettledInterest() { return settledInterest; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }
}
