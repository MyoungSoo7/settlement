package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.SalesChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * insurance_policies 테이블 매핑 (V1).
 *
 * <p>도메인 {@link Policy} 는 전이에 필요한 필드만 들고 있다 — SoR 전용 컬럼
 * (application_id·product_code·coverage_amount·payment_cycle_months)은 이 엔티티가 보존하고,
 * 어댑터는 {@link #applyTransitionResult} 로 <b>전이 가능 필드만</b> 도메인 결과를 반영한다.
 *
 * <p>created_at/updated_at 은 DB DEFAULT NOW() 위임(insertable=false) —
 * card-service {@code CardAccountJpaEntity} 와 동일 관례.
 */
@Entity
@Table(name = "insurance_policies", schema = "opslab")
public class PolicyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_number", nullable = false, length = 64)
    private String policyNumber;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "fc_id", nullable = false, length = 64)
    private String fcId;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PolicyStatus status;

    @Column(name = "premium_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal premiumAmount;

    @Column(name = "coverage_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal coverageAmount;

    @Column(name = "payment_cycle_months", nullable = false)
    private int paymentCycleMonths;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "lapsed_at")
    private LocalDate lapsedAt;

    @Column(name = "consecutive_premium_failures", nullable = false)
    private int consecutivePremiumFailures;

    @Enumerated(EnumType.STRING)
    @Column(name = "sales_channel", nullable = false, length = 20)
    private SalesChannel salesChannel;

    @Column(name = "partner_bank_code", length = 32)
    private String partnerBankCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PolicyJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 계약 발행 INSERT 용 — 청약 승인 경로 전용. SoR 전용 컬럼은 attributes 로 받는다. */
    public static PolicyJpaEntity fromIssued(
            Policy policy, java.util.UUID applicationId, String productCode,
            BigDecimal coverageAmount, int paymentCycleMonths) {
        PolicyJpaEntity e = new PolicyJpaEntity();
        e.policyId = java.util.UUID.fromString(policy.getPolicyId());
        e.policyNumber = policy.getPolicyNumber();
        e.applicationId = applicationId;
        e.fcId = policy.getFcId();
        e.productCode = productCode;
        e.status = policy.getStatus();
        e.premiumAmount = policy.getPremiumAmount();
        e.coverageAmount = coverageAmount;
        e.paymentCycleMonths = paymentCycleMonths;
        e.effectiveDate = policy.getEffectiveDate();
        e.maturityDate = policy.getMaturityDate();
        e.lapsedAt = policy.getLapsedAt();
        e.consecutivePremiumFailures = policy.getConsecutivePremiumFailures();
        e.salesChannel = policy.getSalesChannel();
        e.partnerBankCode = policy.getPartnerBankCode();
        return e;
    }

    /** DB 행 → 도메인 재구성. 전이 로직은 도메인이 강제한다. */
    public Policy toDomain() {
        return Policy.builder()
                .id(id)
                .policyId(policyId.toString())
                .policyNumber(policyNumber)
                .status(status)
                .effectiveDate(effectiveDate)
                .maturityDate(maturityDate)
                .lapsedAt(lapsedAt)
                .consecutivePremiumFailures(consecutivePremiumFailures)
                .premiumAmount(premiumAmount)
                .fcId(fcId)
                .salesChannel(salesChannel)
                .partnerBankCode(partnerBankCode)
                .build();
    }

    /**
     * 도메인 전이 결과 반영 — 전이로 변할 수 있는 3개 필드만.
     * 나머지 SoR 컬럼(application_id·product_code·coverage_amount 등)은 그대로 보존된다.
     */
    public void applyTransitionResult(Policy policy) {
        this.status = policy.getStatus();
        this.lapsedAt = policy.getLapsedAt();
        this.consecutivePremiumFailures = policy.getConsecutivePremiumFailures();
    }

    public Long getId() {
        return id;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public BigDecimal getCoverageAmount() {
        return coverageAmount;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getFcId() {
        return fcId;
    }
}
