package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.ApplicationStatus;
import github.lms.lemuel.insurance.domain.InsuranceApplication;
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
import java.util.UUID;

/**
 * insurance_applications 테이블 매핑 (V1 + V6 채널).
 *
 * <p>reviewed_at/decided_at 은 상태 전이 반영 시 어댑터가 찍는다 — 도메인은 시각을 들지 않는다.
 * created_at/updated_at/submitted_at 은 DB DEFAULT NOW() 위임(insertable=false).
 */
@Entity
@Table(name = "insurance_applications", schema = "opslab")
public class InsuranceApplicationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "consultation_id")
    private UUID consultationId;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "fc_id", nullable = false, length = 64)
    private String fcId;

    @Column(name = "insured_name", nullable = false, length = 100)
    private String insuredName;

    @Column(name = "contractor_name", nullable = false, length = 100)
    private String contractorName;

    @Column(name = "desired_coverage", nullable = false, precision = 19, scale = 2)
    private BigDecimal desiredCoverage;

    @Column(name = "desired_premium", nullable = false, precision = 19, scale = 2)
    private BigDecimal desiredPremium;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "sales_channel", nullable = false, length = 20)
    private SalesChannel salesChannel;

    @Column(name = "partner_bank_code", length = 32)
    private String partnerBankCode;

    @Column(name = "submitted_at", insertable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected InsuranceApplicationJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 신규 접수 INSERT 용. */
    public static InsuranceApplicationJpaEntity fromDomain(InsuranceApplication a) {
        InsuranceApplicationJpaEntity e = new InsuranceApplicationJpaEntity();
        e.applicationId = UUID.fromString(a.getApplicationId());
        e.consultationId = a.getConsultationId() != null ? UUID.fromString(a.getConsultationId()) : null;
        e.productCode = a.getProductCode();
        e.fcId = a.getFcId();
        e.insuredName = a.getInsuredName();
        e.contractorName = a.getContractorName();
        e.desiredCoverage = a.getDesiredCoverage();
        e.desiredPremium = a.getDesiredPremium();
        e.status = a.getStatus();
        e.rejectReason = a.getRejectReason();
        e.salesChannel = a.getSalesChannel();
        e.partnerBankCode = a.getPartnerBankCode();
        return e;
    }

    /** DB 행 → 도메인 재구성. 전이 로직은 도메인이 강제한다. */
    public InsuranceApplication toDomain() {
        return InsuranceApplication.builder()
                .id(id)
                .applicationId(applicationId.toString())
                .consultationId(consultationId != null ? consultationId.toString() : null)
                .productCode(productCode)
                .fcId(fcId)
                .insuredName(insuredName)
                .contractorName(contractorName)
                .desiredCoverage(desiredCoverage)
                .desiredPremium(desiredPremium)
                .status(status)
                .rejectReason(rejectReason)
                .salesChannel(salesChannel)
                .partnerBankCode(partnerBankCode)
                .build();
    }

    /**
     * 도메인 전이 결과 반영 — status·rejectReason + 전이 시각 스탬프.
     * 심사 착수 시 reviewed_at, 종결(APPROVED·REJECTED) 시 decided_at 을 어댑터가 찍는다.
     */
    public void applyTransitionResult(InsuranceApplication a) {
        ApplicationStatus previous = this.status;
        this.status = a.getStatus();
        this.rejectReason = a.getRejectReason();
        if (previous != a.getStatus()) {
            Instant now = Instant.now();
            if (a.getStatus() == ApplicationStatus.UNDER_REVIEW) {
                this.reviewedAt = now;
            }
            if (a.getStatus().isTerminal()) {
                this.decidedAt = now;
            }
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public ApplicationStatus getStatus() {
        return status;
    }
}
