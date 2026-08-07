package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 청약(InsuranceApplication) 애그리거트 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p>언더라이팅 상태머신({@link ApplicationStatus})의 허용 전이 3개를 각 메서드가 강제한다.
 * 승인(APPROVED)은 계약(Policy) 발행 + 초년도 수수료 스케줄 확정(D4)으로 이어지는 돈 경로다 —
 * 승인 오케스트레이션(완전판매 교부 게이트 포함)은 응용 서비스 몫이고,
 * 이 애그리거트는 전이 합법성만 책임진다.
 *
 * <p>채널 불변식은 {@link Policy} 와 동형: BANCA ↔ 판매 은행 존재.
 */
public class InsuranceApplication {

    private Long id;
    private final String applicationId;       // UUID — 자연키
    private final String consultationId;      // 상담 경유 유입 시 (nullable)
    private final String productCode;
    private final String fcId;                // 접수자 — FC id 또는 은행 창구 직원 id
    private final String insuredName;
    private final String contractorName;
    private final BigDecimal desiredCoverage;
    private final BigDecimal desiredPremium;  // 연 보험료 기준
    private ApplicationStatus status;
    private String rejectReason;
    private final SalesChannel salesChannel;
    private final String partnerBankCode;

    private InsuranceApplication(Builder b) {
        this.id              = b.id;
        this.applicationId   = Objects.requireNonNull(b.applicationId, "applicationId");
        this.consultationId  = b.consultationId;
        this.productCode     = Objects.requireNonNull(b.productCode, "productCode");
        this.fcId            = Objects.requireNonNull(b.fcId, "fcId");
        this.insuredName     = Objects.requireNonNull(b.insuredName, "insuredName");
        this.contractorName  = Objects.requireNonNull(b.contractorName, "contractorName");
        this.desiredCoverage = Objects.requireNonNull(b.desiredCoverage, "desiredCoverage");
        this.desiredPremium  = Objects.requireNonNull(b.desiredPremium, "desiredPremium");
        this.status          = b.status != null ? b.status : ApplicationStatus.SUBMITTED;
        this.rejectReason    = b.rejectReason;
        this.salesChannel    = b.salesChannel != null ? b.salesChannel : SalesChannel.FC;
        this.partnerBankCode = b.partnerBankCode;

        if (desiredCoverage.signum() <= 0) {
            throw new InvalidApplicationException(
                    "보장금액은 0 보다 커야 합니다: " + desiredCoverage.toPlainString());
        }
        if (desiredPremium.signum() <= 0) {
            throw new InvalidApplicationException(
                    "보험료는 0 보다 커야 합니다: " + desiredPremium.toPlainString());
        }
        if ((this.salesChannel == SalesChannel.BANCA) != (this.partnerBankCode != null)) {
            throw new InvalidSalesChannelException(
                    "BANCA 청약은 판매 은행 코드가 필수이고, FC 청약은 판매 은행을 가질 수 없습니다: "
                            + "channel=" + this.salesChannel + ", partnerBankCode=" + this.partnerBankCode);
        }
    }

    /** 신규 청약 접수 — applicationId 자동 채번, SUBMITTED 로 시작. */
    public static InsuranceApplication submit(String consultationId, String productCode, String fcId,
                                              String insuredName, String contractorName,
                                              BigDecimal desiredCoverage, BigDecimal desiredPremium,
                                              SalesChannel salesChannel, String partnerBankCode) {
        return builder()
                .applicationId(UUID.randomUUID().toString())
                .consultationId(consultationId)
                .productCode(productCode)
                .fcId(fcId)
                .insuredName(insuredName)
                .contractorName(contractorName)
                .desiredCoverage(desiredCoverage)
                .desiredPremium(desiredPremium)
                .salesChannel(salesChannel)
                .partnerBankCode(partnerBankCode)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 상태 전이 메서드 — ApplicationStatus 전이표 3개만 허용
    // ─────────────────────────────────────────────────────────────────────

    /** 심사 착수 — SUBMITTED → UNDER_REVIEW. */
    public void startReview() {
        transitionTo(ApplicationStatus.UNDER_REVIEW);
    }

    /** 승인 — UNDER_REVIEW → APPROVED (terminal). 계약 발행은 응용 서비스가 이어서 수행한다. */
    public void approve() {
        transitionTo(ApplicationStatus.APPROVED);
    }

    /**
     * 반려 — UNDER_REVIEW → REJECTED (terminal). 사유 필수.
     */
    public void reject(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new InvalidApplicationException("반려 사유는 빈 값일 수 없습니다");
        }
        transitionTo(ApplicationStatus.REJECTED);
        this.rejectReason = reason;
    }

    private void transitionTo(ApplicationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidApplicationTransitionException(status, target);
        }
        this.status = target;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Getters (setter 없음 — guard.mjs OO 규칙)
    // ─────────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getApplicationId() {
        return applicationId;
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

    public String getContractorName() {
        return contractorName;
    }

    public BigDecimal getDesiredCoverage() {
        return desiredCoverage;
    }

    public BigDecimal getDesiredPremium() {
        return desiredPremium;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public SalesChannel getSalesChannel() {
        return salesChannel;
    }

    public String getPartnerBankCode() {
        return partnerBankCode;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Builder (영속성 어댑터 재구성 + 정적 팩토리 공용)
    // ─────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String applicationId;
        private String consultationId;
        private String productCode;
        private String fcId;
        private String insuredName;
        private String contractorName;
        private BigDecimal desiredCoverage;
        private BigDecimal desiredPremium;
        private ApplicationStatus status;
        private String rejectReason;
        private SalesChannel salesChannel;
        private String partnerBankCode;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder applicationId(String v) {
            this.applicationId = v;
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

        public Builder contractorName(String v) {
            this.contractorName = v;
            return this;
        }

        public Builder desiredCoverage(BigDecimal v) {
            this.desiredCoverage = v;
            return this;
        }

        public Builder desiredPremium(BigDecimal v) {
            this.desiredPremium = v;
            return this;
        }

        public Builder status(ApplicationStatus v) {
            this.status = v;
            return this;
        }

        public Builder rejectReason(String v) {
            this.rejectReason = v;
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

        public InsuranceApplication build() {
            return new InsuranceApplication(this);
        }
    }
}
