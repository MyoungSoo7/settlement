package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.Gender;
import github.lms.lemuel.insurance.domain.ProposalQuote;
import github.lms.lemuel.insurance.domain.ProposalStatus;
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
 * proposal_quotes 테이블 매핑 (V9).
 *
 * <p>산출 스냅샷 컬럼(요율·보험료·보험나이)은 INSERT 후 불변 — 전이 반영은
 * status·converted_application_id 만 만진다({@link #applyTransitionResult}).
 */
@Entity
@Table(name = "proposal_quotes", schema = "opslab")
public class ProposalQuoteJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private UUID proposalId;

    @Column(name = "consultation_id")
    private UUID consultationId;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "fc_id", nullable = false, length = 64)
    private String fcId;

    @Column(name = "insured_name", nullable = false, length = 100)
    private String insuredName;

    /** Gender enum 의 name — String 매핑이어야 varchar(1) 스키마와 validate 가 일치한다(요율 엔티티와 동형). */
    @Column(name = "insured_gender", nullable = false, length = 1)
    private String insuredGender;

    @Column(name = "insurance_age", nullable = false)
    private int insuranceAge;

    @Column(name = "coverage_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal coverageAmount;

    @Column(name = "payment_term_years", nullable = false)
    private int paymentTermYears;

    @Column(name = "rate_table_id", nullable = false)
    private long rateTableId;

    @Column(name = "applied_rate_per_mille", nullable = false, precision = 9, scale = 4)
    private BigDecimal appliedRatePerMille;

    @Column(name = "annual_premium", nullable = false, precision = 19, scale = 2)
    private BigDecimal annualPremium;

    @Enumerated(EnumType.STRING)
    @Column(name = "sales_channel", nullable = false, length = 20)
    private SalesChannel salesChannel;

    @Column(name = "partner_bank_code", length = 32)
    private String partnerBankCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProposalStatus status;

    @Column(name = "quoted_on", nullable = false)
    private LocalDate quotedOn;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(name = "converted_application_id")
    private UUID convertedApplicationId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ProposalQuoteJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 신규 산출 INSERT 용. */
    public static ProposalQuoteJpaEntity fromDomain(ProposalQuote p) {
        ProposalQuoteJpaEntity e = new ProposalQuoteJpaEntity();
        e.proposalId = UUID.fromString(p.getProposalId());
        e.consultationId = p.getConsultationId() != null ? UUID.fromString(p.getConsultationId()) : null;
        e.productCode = p.getProductCode();
        e.fcId = p.getFcId();
        e.insuredName = p.getInsuredName();
        e.insuredGender = p.getInsuredGender().name();
        e.insuranceAge = p.getInsuranceAge();
        e.coverageAmount = p.getCoverageAmount();
        e.paymentTermYears = p.getPaymentTermYears();
        e.rateTableId = p.getRateTableId();
        e.appliedRatePerMille = p.getAppliedRatePerMille();
        e.annualPremium = p.getAnnualPremium();
        e.salesChannel = p.getSalesChannel();
        e.partnerBankCode = p.getPartnerBankCode();
        e.status = p.getStatus();
        e.quotedOn = p.getQuotedOn();
        e.validUntil = p.getValidUntil();
        e.convertedApplicationId =
                p.getConvertedApplicationId() != null ? UUID.fromString(p.getConvertedApplicationId()) : null;
        return e;
    }

    /** DB 행 → 도메인 재구성. 전이 로직은 도메인이 강제한다. */
    public ProposalQuote toDomain() {
        return ProposalQuote.builder()
                .id(id)
                .proposalId(proposalId.toString())
                .consultationId(consultationId != null ? consultationId.toString() : null)
                .productCode(productCode)
                .fcId(fcId)
                .insuredName(insuredName)
                .insuredGender(Gender.valueOf(insuredGender))
                .insuranceAge(insuranceAge)
                .coverageAmount(coverageAmount)
                .paymentTermYears(paymentTermYears)
                .rateTableId(rateTableId)
                .appliedRatePerMille(appliedRatePerMille)
                .annualPremium(annualPremium)
                .salesChannel(salesChannel)
                .partnerBankCode(partnerBankCode)
                .status(status)
                .quotedOn(quotedOn)
                .validUntil(validUntil)
                .convertedApplicationId(
                        convertedApplicationId != null ? convertedApplicationId.toString() : null)
                .build();
    }

    /** 도메인 전이 결과 반영 — status·converted_application_id 만. 산출 스냅샷은 불변. */
    public void applyTransitionResult(ProposalQuote p) {
        this.status = p.getStatus();
        this.convertedApplicationId =
                p.getConvertedApplicationId() != null ? UUID.fromString(p.getConvertedApplicationId()) : null;
    }

    public Long getId() {
        return id;
    }

    public UUID getProposalId() {
        return proposalId;
    }

    public ProposalStatus getStatus() {
        return status;
    }
}
