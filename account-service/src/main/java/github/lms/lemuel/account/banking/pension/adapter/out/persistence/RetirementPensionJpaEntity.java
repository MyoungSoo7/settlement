package github.lms.lemuel.account.banking.pension.adapter.out.persistence;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
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
 * 퇴직연금 계약 영속 엔티티 (retirement_pensions).
 *
 * <p>거래 이력은 {@code pension_transactions} 로 분리하고 여기에 컬렉션 매핑을 두지 않는다 —
 * append-only 자식을 {@code @OneToMany} 로 물면 저장 때마다 전체 컬렉션을 동기화하게 되는데,
 * 실제로 필요한 동작은 "새 거래 몇 건 덧붙이기"뿐이다.
 */
@Entity
@Table(name = "retirement_pensions")
public class RetirementPensionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscriber_id", nullable = false, length = 64)
    private String subscriberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scheme", nullable = false, length = 10)
    private PensionScheme scheme;

    /** DB·DC 는 필수, IRP 는 null — CHECK 제약이 스키마 수준에서 강제한다. */
    @Column(name = "employer_name", length = 100)
    private String employerName;

    /** 수급 개시 연령 판정의 근거 — 가입 시 1회 기록하고 이후 변경 경로가 없다. */
    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "annual_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal annualRate;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "product_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal productRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PensionStatus status;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    /** 직전 이자 확정일 — 다음 이자의 기산일. NULL 이면 개설일부터 기산한다. */
    @Column(name = "last_interest_settled_on")
    private LocalDate lastInterestSettledOn;

    @Column(name = "benefit_started_on")
    private LocalDate benefitStartedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_type", length = 20)
    private BenefitType benefitType;

    @Column(name = "accumulated_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal accumulatedAmount;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    protected RetirementPensionJpaEntity() { }

    public RetirementPensionJpaEntity(Long id, String subscriberId, PensionScheme scheme, String employerName,
                                      LocalDate birthDate, BigDecimal annualRate, String productName,
                                      BigDecimal productRate, PensionStatus status, LocalDate openedOn,
                                      LocalDate lastInterestSettledOn, LocalDate benefitStartedOn,
                                      BenefitType benefitType, BigDecimal accumulatedAmount, long nextSeq) {
        this.id = id;
        this.subscriberId = subscriberId;
        this.scheme = scheme;
        this.employerName = employerName;
        this.birthDate = birthDate;
        this.annualRate = annualRate;
        this.productName = productName;
        this.productRate = productRate;
        this.status = status;
        this.openedOn = openedOn;
        this.lastInterestSettledOn = lastInterestSettledOn;
        this.benefitType = benefitType;
        this.benefitStartedOn = benefitStartedOn;
        this.accumulatedAmount = accumulatedAmount;
        this.nextSeq = nextSeq;
    }

    public Long getId() { return id; }
    public String getSubscriberId() { return subscriberId; }
    public PensionScheme getScheme() { return scheme; }
    public String getEmployerName() { return employerName; }
    public LocalDate getBirthDate() { return birthDate; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public String getProductName() { return productName; }
    public BigDecimal getProductRate() { return productRate; }
    public PensionStatus getStatus() { return status; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getLastInterestSettledOn() { return lastInterestSettledOn; }
    public LocalDate getBenefitStartedOn() { return benefitStartedOn; }
    public BenefitType getBenefitType() { return benefitType; }
    public BigDecimal getAccumulatedAmount() { return accumulatedAmount; }
    public long getNextSeq() { return nextSeq; }
}
