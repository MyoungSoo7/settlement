package github.lms.lemuel.account.banking.savings.adapter.out.persistence;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
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
 * installment_savings 테이블 매핑 (V20260809031500).
 *
 * <p>{@code @Table} 에 schema 를 쓰지 않는다 — 물리 스키마(opslab)는
 * {@code hibernate.default_schema} 가 붙이므로, 여기에 또 쓰면 환경마다 스키마가 다를 때 깨진다.
 */
@Entity
@Table(name = "installment_savings")
public class InstallmentSavingsJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "depositor_id", nullable = false, length = 64)
    private String depositorId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(name = "savings_type", nullable = false, length = 20)
    private SavingsType savingsType;

    @Column(name = "monthly_amount", precision = 19, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(name = "payment_limit", precision = 19, scale = 2)
    private BigDecimal paymentLimit;

    @Column(name = "annual_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal annualRate;

    @Column(name = "early_termination_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal earlyTerminationRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SavingsStatus status;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "settled_interest", precision = 19, scale = 2)
    private BigDecimal settledInterest;

    @Column(name = "payout_amount", precision = 19, scale = 2)
    private BigDecimal payoutAmount;

    protected InstallmentSavingsJpaEntity() {
    }

    public InstallmentSavingsJpaEntity(Long id, String depositorId, String productName,
                                       SavingsType savingsType, BigDecimal monthlyAmount,
                                       BigDecimal paymentLimit, BigDecimal annualRate,
                                       BigDecimal earlyTerminationRate, int termMonths,
                                       LocalDate openedOn, LocalDate maturityDate,
                                       SavingsStatus status, LocalDate closedOn,
                                       BigDecimal settledInterest, BigDecimal payoutAmount) {
        this.id = id;
        this.depositorId = depositorId;
        this.productName = productName;
        this.savingsType = savingsType;
        this.monthlyAmount = monthlyAmount;
        this.paymentLimit = paymentLimit;
        this.annualRate = annualRate;
        this.earlyTerminationRate = earlyTerminationRate;
        this.termMonths = termMonths;
        this.openedOn = openedOn;
        this.maturityDate = maturityDate;
        this.status = status;
        this.closedOn = closedOn;
        this.settledInterest = settledInterest;
        this.payoutAmount = payoutAmount;
    }

    public static InstallmentSavingsJpaEntity fromDomain(InstallmentSavings savings) {
        return new InstallmentSavingsJpaEntity(
                savings.getId(),
                savings.getDepositorId(),
                savings.getProductName(),
                savings.getSavingsType(),
                savings.getMonthlyAmount(),
                savings.getPaymentLimit(),
                savings.getAnnualRate(),
                savings.getEarlyTerminationRate(),
                savings.getTermMonths(),
                savings.getOpenedOn(),
                savings.getMaturityDate(),
                savings.getStatus(),
                savings.getClosedOn(),
                savings.getSettledInterest(),
                savings.getPayoutAmount());
    }

    public Long getId() { return id; }
    public String getDepositorId() { return depositorId; }
    public String getProductName() { return productName; }
    public SavingsType getSavingsType() { return savingsType; }
    public BigDecimal getMonthlyAmount() { return monthlyAmount; }
    public BigDecimal getPaymentLimit() { return paymentLimit; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public BigDecimal getEarlyTerminationRate() { return earlyTerminationRate; }
    public int getTermMonths() { return termMonths; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public SavingsStatus getStatus() { return status; }
    public LocalDate getClosedOn() { return closedOn; }
    public BigDecimal getSettledInterest() { return settledInterest; }
    public BigDecimal getPayoutAmount() { return payoutAmount; }
}
