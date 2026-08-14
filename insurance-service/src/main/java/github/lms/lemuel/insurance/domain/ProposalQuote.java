package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidProposalException;
import github.lms.lemuel.insurance.domain.exception.InvalidProposalTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;
import github.lms.lemuel.insurance.domain.exception.ProposalExpiredException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 가입설계(ProposalQuote) 애그리거트 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p>요율 기반 산출 결과의 <b>불변 스냅샷</b>이다(D-P2): 산출에 쓴 요율 행·적용 요율·보험나이·
 * 보험료가 생성 시점에 고정되고 이후 바뀌지 않는다. 재산출은 새 설계를 만든다.
 *
 * <p>상태머신({@link ProposalStatus})의 허용 전이 2개를 각 메서드가 강제한다.
 * 전환(CONVERTED)은 산출 보험료가 서버 주입으로 청약에 넘어가는 돈 경로 입구다(D-P3) —
 * 청약 생성 오케스트레이션은 응용 서비스 몫이고, 이 애그리거트는 전이 합법성
 * (유효기간·1설계 1청약)만 책임진다.
 *
 * <p>채널 불변식은 {@link InsuranceApplication}·{@link Policy} 와 동형: BANCA ↔ 판매 은행 존재.
 */
public class ProposalQuote {

    /** 설계 유효기간(일) — 산출일 + 30일까지 전환 가능. */
    public static final int VALIDITY_DAYS = 30;

    private Long id;
    private final String proposalId;          // UUID — 자연키
    private final String consultationId;      // 상담 경유 유입 시 (nullable)
    private final String productCode;
    private final String fcId;                // 설계자 — 소유권 대조 기준(IDOR)
    private final String insuredName;
    private final Gender insuredGender;
    private final int insuranceAge;           // 산출 시점 보험나이 (생년월일 미보존 — PII 최소화)
    private final BigDecimal coverageAmount;
    private final int paymentTermYears;
    private final long rateTableId;           // 산출에 쓴 요율 행 (D-P2)
    private final BigDecimal appliedRatePerMille;
    private final BigDecimal annualPremium;   // 산출 결과 — 원 단위
    private final SalesChannel salesChannel;
    private final String partnerBankCode;
    private ProposalStatus status;
    private final LocalDate quotedOn;
    private final LocalDate validUntil;
    private String convertedApplicationId;    // CONVERTED 시 생성된 청약 (1설계 1청약)

    private ProposalQuote(Builder b) {
        this.id                     = b.id;
        this.proposalId             = Objects.requireNonNull(b.proposalId, "proposalId");
        this.consultationId         = b.consultationId;
        this.productCode            = Objects.requireNonNull(b.productCode, "productCode");
        this.fcId                   = Objects.requireNonNull(b.fcId, "fcId");
        this.insuredName            = Objects.requireNonNull(b.insuredName, "insuredName");
        this.insuredGender          = Objects.requireNonNull(b.insuredGender, "insuredGender");
        this.insuranceAge           = b.insuranceAge;
        this.coverageAmount         = Objects.requireNonNull(b.coverageAmount, "coverageAmount");
        this.paymentTermYears       = b.paymentTermYears;
        this.rateTableId            = b.rateTableId;
        this.appliedRatePerMille    = Objects.requireNonNull(b.appliedRatePerMille, "appliedRatePerMille");
        this.annualPremium          = Objects.requireNonNull(b.annualPremium, "annualPremium");
        this.salesChannel           = b.salesChannel != null ? b.salesChannel : SalesChannel.FC;
        this.partnerBankCode        = b.partnerBankCode;
        this.status                 = b.status != null ? b.status : ProposalStatus.QUOTED;
        this.quotedOn               = Objects.requireNonNull(b.quotedOn, "quotedOn");
        this.validUntil             = Objects.requireNonNull(b.validUntil, "validUntil");
        this.convertedApplicationId = b.convertedApplicationId;

        if (insuranceAge < 0) {
            throw new InvalidProposalException("보험나이는 음수일 수 없습니다: " + insuranceAge);
        }
        if (coverageAmount.signum() <= 0) {
            throw new InvalidProposalException(
                    "보장금액은 0 보다 커야 합니다: " + coverageAmount.toPlainString());
        }
        if (paymentTermYears <= 0) {
            throw new InvalidProposalException("납입기간은 0 보다 커야 합니다: " + paymentTermYears);
        }
        if (rateTableId <= 0) {
            throw new InvalidProposalException("요율 행 식별자가 유효하지 않습니다: " + rateTableId);
        }
        if (annualPremium.signum() <= 0) {
            throw new InvalidProposalException(
                    "산출 보험료는 0 보다 커야 합니다: " + annualPremium.toPlainString());
        }
        if (validUntil.isBefore(quotedOn)) {
            throw new InvalidProposalException(
                    "유효기한이 산출일보다 앞설 수 없습니다: quotedOn=" + quotedOn + ", validUntil=" + validUntil);
        }
        if ((this.salesChannel == SalesChannel.BANCA) != (this.partnerBankCode != null)) {
            throw new InvalidSalesChannelException(
                    "BANCA 설계는 판매 은행 코드가 필수이고, FC 설계는 판매 은행을 가질 수 없습니다: "
                            + "channel=" + this.salesChannel + ", partnerBankCode=" + this.partnerBankCode);
        }
        if ((this.status == ProposalStatus.CONVERTED) != (this.convertedApplicationId != null)) {
            throw new InvalidProposalException(
                    "CONVERTED 상태와 전환된 청약 식별자는 함께여야 합니다: status=" + this.status
                            + ", convertedApplicationId=" + this.convertedApplicationId);
        }
    }

    /**
     * 신규 가입설계 산출 — proposalId 자동 채번, QUOTED 로 시작.
     * 연 보험료는 {@link PremiumRater} 로 이 자리에서 계산·고정된다(외부에서 주입받지 않는다).
     */
    public static ProposalQuote quote(String consultationId, String productCode, String fcId,
                                      String insuredName, Gender insuredGender, int insuranceAge,
                                      BigDecimal coverageAmount, int paymentTermYears,
                                      AppliedRate rate, SalesChannel salesChannel,
                                      String partnerBankCode, LocalDate quotedOn) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(quotedOn, "quotedOn");
        return builder()
                .proposalId(UUID.randomUUID().toString())
                .consultationId(consultationId)
                .productCode(productCode)
                .fcId(fcId)
                .insuredName(insuredName)
                .insuredGender(insuredGender)
                .insuranceAge(insuranceAge)
                .coverageAmount(coverageAmount)
                .paymentTermYears(paymentTermYears)
                .rateTableId(rate.rateTableId())
                .appliedRatePerMille(rate.ratePerMille())
                .annualPremium(PremiumRater.annualPremium(coverageAmount, rate.ratePerMille()))
                .salesChannel(salesChannel)
                .partnerBankCode(partnerBankCode)
                .quotedOn(quotedOn)
                .validUntil(quotedOn.plusDays(VALIDITY_DAYS))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 상태 전이 메서드 — ProposalStatus 전이표 2개만 허용
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 청약 전환 — QUOTED → CONVERTED (terminal).
     * 유효기간이 지났으면 전환을 거부한다(새 설계를 만들어야 한다).
     *
     * @param applicationId 이 설계로 생성된 청약 식별자 (1설계 1청약)
     */
    public void convert(String applicationId, LocalDate today) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(today, "today");
        // 이미 만기 처리된 설계도, 유효기한이 지난 설계도 같은 사유다 — 만료 전용 예외(409)
        if (status == ProposalStatus.EXPIRED || today.isAfter(validUntil)) {
            throw new ProposalExpiredException(proposalId, validUntil);
        }
        transitionTo(ProposalStatus.CONVERTED);
        this.convertedApplicationId = applicationId;
    }

    /**
     * 만기 처리 — QUOTED → EXPIRED (terminal). 만기 스윕 배치가 집행한다.
     * 유효기간이 지나지 않았으면 거부 — 스캔 술어와 도메인 가드가 어긋나면 조용히 넘어가는
     * 대신 예외로 드러난다 ({@link Policy} 만기 배치와 동일 원칙).
     */
    public void expire(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (!today.isAfter(validUntil)) {
            throw new InvalidProposalException(
                    "유효기간이 지나지 않은 설계는 만기 처리할 수 없습니다: proposalId=" + proposalId
                            + ", validUntil=" + validUntil + ", today=" + today);
        }
        transitionTo(ProposalStatus.EXPIRED);
    }

    private void transitionTo(ProposalStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidProposalTransitionException(status, target);
        }
        this.status = target;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Getters (setter 없음 — guard.mjs OO 규칙)
    // ─────────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getProposalId() {
        return proposalId;
    }

    public String getConsultationId() {
        return consultationId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getFcId() {
        return fcId;
    }

    public String getInsuredName() {
        return insuredName;
    }

    public Gender getInsuredGender() {
        return insuredGender;
    }

    public int getInsuranceAge() {
        return insuranceAge;
    }

    public BigDecimal getCoverageAmount() {
        return coverageAmount;
    }

    public int getPaymentTermYears() {
        return paymentTermYears;
    }

    public long getRateTableId() {
        return rateTableId;
    }

    public BigDecimal getAppliedRatePerMille() {
        return appliedRatePerMille;
    }

    public BigDecimal getAnnualPremium() {
        return annualPremium;
    }

    public SalesChannel getSalesChannel() {
        return salesChannel;
    }

    public String getPartnerBankCode() {
        return partnerBankCode;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public LocalDate getQuotedOn() {
        return quotedOn;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public String getConvertedApplicationId() {
        return convertedApplicationId;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Builder (영속성 어댑터 재구성 + 정적 팩토리 공용)
    // ─────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String proposalId;
        private String consultationId;
        private String productCode;
        private String fcId;
        private String insuredName;
        private Gender insuredGender;
        private int insuranceAge;
        private BigDecimal coverageAmount;
        private int paymentTermYears;
        private long rateTableId;
        private BigDecimal appliedRatePerMille;
        private BigDecimal annualPremium;
        private SalesChannel salesChannel;
        private String partnerBankCode;
        private ProposalStatus status;
        private LocalDate quotedOn;
        private LocalDate validUntil;
        private String convertedApplicationId;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder proposalId(String v) {
            this.proposalId = v;
            return this;
        }

        public Builder consultationId(String v) {
            this.consultationId = v;
            return this;
        }

        public Builder productCode(String v) {
            this.productCode = v;
            return this;
        }

        public Builder fcId(String v) {
            this.fcId = v;
            return this;
        }

        public Builder insuredName(String v) {
            this.insuredName = v;
            return this;
        }

        public Builder insuredGender(Gender v) {
            this.insuredGender = v;
            return this;
        }

        public Builder insuranceAge(int v) {
            this.insuranceAge = v;
            return this;
        }

        public Builder coverageAmount(BigDecimal v) {
            this.coverageAmount = v;
            return this;
        }

        public Builder paymentTermYears(int v) {
            this.paymentTermYears = v;
            return this;
        }

        public Builder rateTableId(long v) {
            this.rateTableId = v;
            return this;
        }

        public Builder appliedRatePerMille(BigDecimal v) {
            this.appliedRatePerMille = v;
            return this;
        }

        public Builder annualPremium(BigDecimal v) {
            this.annualPremium = v;
            return this;
        }

        public Builder salesChannel(SalesChannel v) {
            this.salesChannel = v;
            return this;
        }

        public Builder partnerBankCode(String v) {
            this.partnerBankCode = v;
            return this;
        }

        public Builder status(ProposalStatus v) {
            this.status = v;
            return this;
        }

        public Builder quotedOn(LocalDate v) {
            this.quotedOn = v;
            return this;
        }

        public Builder validUntil(LocalDate v) {
            this.validUntil = v;
            return this;
        }

        public Builder convertedApplicationId(String v) {
            this.convertedApplicationId = v;
            return this;
        }

        public ProposalQuote build() {
            return new ProposalQuote(this);
        }
    }
}
