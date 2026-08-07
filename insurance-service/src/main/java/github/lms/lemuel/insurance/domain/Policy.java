package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import github.lms.lemuel.insurance.domain.exception.InvalidSalesChannelException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 보험 계약(Policy) 애그리거트 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p><b>D1 — System of Record</b>: 납입 실패→실효, 부활, 만기 판정을 이 서비스가 직접 수행한다.
 * 외부 보험사 연동 없음.
 *
 * <p><b>D7 — 상태머신</b>: 허용 전이 7개를 각 메서드가 강제한다.
 * 비허용 전이는 {@link InvalidPolicyTransitionException} 을 던진다 — 조용한 무시 금지.
 *
 * <pre>
 *             [2회 연속 납입 실패]
 *   ACTIVE ────────────────────→ LAPSED
 *     │  ←──────────────────────── │    (부활: 실효일부터 부활창구 이내)
 *     │          [만기]            │    [부활창구 경과 소멸]
 *     │ ──────→ EXPIRED  ←──────── │
 *     │ [해지]                     │
 *     └──────→ SURRENDERED         │
 *     │ [청약철회 15일 이내]        │
 *     └──────────────────────────── └──→ CANCELLED
 * </pre>
 *
 * <p>Terminal 상태(SURRENDERED · EXPIRED · CANCELLED)에서 나가는 전이는 없다.
 */
public class Policy {

    /** D7: 청약철회 가능 창구 (효력일로부터 이 일수 미만까지). */
    public static final int CANCELLATION_WINDOW_DAYS = 15;

    /**
     * D7: 부활 가능 창구 (실효일로부터 이 개월 수 미만까지).
     * 이 기간이 경과하면 LAPSED → EXPIRED 소멸 처리가 가능하다.
     *
     * <p><b>D6 상수 규율</b>: 개월 수 자체는 {@link CommissionConstants#CLAWBACK_WINDOW_MONTHS} 한 곳에서만
     * 선언된다. 부활 창구와 환수 창구는 같은 값을 공유하며, 여기서 리터럴을 다시 적지 않는다.
     */
    public static final int REINSTATEMENT_WINDOW_MONTHS = CommissionConstants.CLAWBACK_WINDOW_MONTHS;

    private Long id;
    private final String policyId;              // UUID — commission_schedules.policy_id 가 참조 (재구성 시 세팅)
    private final String policyNumber;          // 도메인 자연키 UNIQUE
    private PolicyStatus status;
    private final LocalDate effectiveDate;      // 효력일 — 청약철회 기산점
    private final LocalDate maturityDate;       // 만기일 (nullable)
    private LocalDate lapsedAt;                 // 실효일 — 부활 기간 기산점 (nullable)
    private int consecutivePremiumFailures;     // 연속 납입 실패 횟수
    private final BigDecimal premiumAmount;     // 보험료
    private final String fcId;                  // 설계사 식별자
    private final SalesChannel salesChannel;    // V6: 판매채널 (FC | BANCA)
    private final String partnerBankCode;       // V6: BANCA 판매 은행 코드 (FC 채널이면 null)

    private Policy(Builder b) {
        this.id            = b.id;
        this.policyId      = b.policyId;  // nullable — 영속 어댑터 재구성 시 세팅
        this.policyNumber  = Objects.requireNonNull(b.policyNumber, "policyNumber");
        this.status        = Objects.requireNonNull(b.status, "status");
        this.effectiveDate = Objects.requireNonNull(b.effectiveDate, "effectiveDate");
        this.maturityDate  = b.maturityDate;
        this.lapsedAt      = b.lapsedAt;
        this.consecutivePremiumFailures = b.consecutivePremiumFailures;
        this.premiumAmount = Objects.requireNonNull(b.premiumAmount, "premiumAmount");
        this.fcId          = Objects.requireNonNull(b.fcId, "fcId");
        this.salesChannel  = b.salesChannel != null ? b.salesChannel : SalesChannel.FC;
        this.partnerBankCode = b.partnerBankCode;
        // V6 불변식 — chk_policy_banca_bank 와 동일: BANCA ↔ 판매 은행 존재
        if ((this.salesChannel == SalesChannel.BANCA) != (this.partnerBankCode != null)) {
            throw new InvalidSalesChannelException(
                    "BANCA 채널은 판매 은행 코드가 필수이고, FC 채널은 판매 은행을 가질 수 없습니다: "
                            + "channel=" + this.salesChannel + ", partnerBankCode=" + this.partnerBankCode);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // D7 상태 전이 메서드
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 보험료 납입 실패 기록.
     *
     * <p>D7: 2회 연속 실패에서만 ACTIVE → LAPSED 로 전이된다.
     * 1회 실패로는 전이되지 않는다.
     *
     * @param today 실패 발생일 (실효 시 lapsedAt 에 기록)
     * @return 이 호출로 LAPSED 로 전이되었으면 {@code true}
     * @throws InvalidPolicyTransitionException ACTIVE 가 아닌 상태에서 호출 시
     */
    public boolean recordPremiumFailure(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (status != PolicyStatus.ACTIVE) {
            throw new InvalidPolicyTransitionException(
                    "납입 실패는 ACTIVE 상태에서만 기록할 수 있습니다. 현재=" + status);
        }
        consecutivePremiumFailures++;
        if (consecutivePremiumFailures >= 2) {
            lapsedAt = today;
            transitionTo(PolicyStatus.LAPSED);
            return true;
        }
        return false;
    }

    /**
     * 보험료 납입 성공 기록.
     *
     * <p>ACTIVE 상태에서 연속 납입 실패 카운터를 0으로 리셋한다.
     */
    public void recordPremiumSuccess() {
        if (status == PolicyStatus.ACTIVE) {
            consecutivePremiumFailures = 0;
        }
    }

    /**
     * 부활 — LAPSED → ACTIVE.
     *
     * <p>D7: 실효일로부터 {@value #REINSTATEMENT_WINDOW_MONTHS}개월 이내에만 가능.
     * 부활 성공 시 consecutivePremiumFailures 를 0으로 리셋한다.
     * 부활 횟수 제한 없음.
     *
     * @param today 부활 처리일
     * @throws InvalidPolicyTransitionException LAPSED 가 아니거나 부활 창구 경과 시
     */
    public void reinstate(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (status != PolicyStatus.LAPSED) {
            throw new InvalidPolicyTransitionException(status, PolicyStatus.ACTIVE);
        }
        Objects.requireNonNull(lapsedAt, "lapsedAt 은 LAPSED 상태에서 반드시 설정되어야 합니다");
        long monthsElapsed = ChronoUnit.MONTHS.between(lapsedAt, today);
        if (monthsElapsed >= REINSTATEMENT_WINDOW_MONTHS) {
            throw new InvalidPolicyTransitionException(
                    "실효일(" + lapsedAt + ")로부터 " + REINSTATEMENT_WINDOW_MONTHS
                            + "개월이 경과해 부활 불가능합니다. 경과=" + monthsElapsed + "개월");
        }
        consecutivePremiumFailures = 0;
        transitionTo(PolicyStatus.ACTIVE);
    }

    /**
     * 실효 후 부활 기간 도과 소멸 — LAPSED → EXPIRED.
     *
     * <p>D7: 실효일로부터 {@value #REINSTATEMENT_WINDOW_MONTHS}개월 경과 후에만 호출 가능.
     *
     * @param today 소멸 판정일
     * @throws InvalidPolicyTransitionException LAPSED 가 아니거나 부활 창구 미경과 시
     */
    public void expireAfterLapse(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (status != PolicyStatus.LAPSED) {
            throw new InvalidPolicyTransitionException(status, PolicyStatus.EXPIRED);
        }
        Objects.requireNonNull(lapsedAt, "lapsedAt 은 LAPSED 상태에서 반드시 설정되어야 합니다");
        long monthsElapsed = ChronoUnit.MONTHS.between(lapsedAt, today);
        if (monthsElapsed < REINSTATEMENT_WINDOW_MONTHS) {
            throw new InvalidPolicyTransitionException(
                    "실효일(" + lapsedAt + ")로부터 " + REINSTATEMENT_WINDOW_MONTHS
                            + "개월이 경과하지 않아 소멸 처리할 수 없습니다. 경과=" + monthsElapsed + "개월");
        }
        transitionTo(PolicyStatus.EXPIRED);
    }

    /**
     * 계약자 임의 해지 — ACTIVE → SURRENDERED.
     *
     * @throws InvalidPolicyTransitionException ACTIVE 가 아닌 상태에서 호출 시
     */
    public void surrender() {
        if (status != PolicyStatus.ACTIVE) {
            throw new InvalidPolicyTransitionException(status, PolicyStatus.SURRENDERED);
        }
        transitionTo(PolicyStatus.SURRENDERED);
    }

    /**
     * 만기일 도래 소멸 — ACTIVE → EXPIRED.
     *
     * @throws InvalidPolicyTransitionException ACTIVE 가 아닌 상태에서 호출 시
     */
    public void expire() {
        if (status != PolicyStatus.ACTIVE) {
            throw new InvalidPolicyTransitionException(status, PolicyStatus.EXPIRED);
        }
        transitionTo(PolicyStatus.EXPIRED);
    }

    /**
     * 청약철회 — ACTIVE 또는 LAPSED → CANCELLED.
     *
     * <p>D7: 효력일(effectiveDate)로부터 {@value #CANCELLATION_WINDOW_DAYS}일 미만까지만 가능.
     * 일수 기산: {@code ChronoUnit.DAYS.between(effectiveDate, today)} 가 15 미만이어야 한다
     * (effectiveDate 당일 = 0일, effectiveDate+14일 = 14일 → 허용 / effectiveDate+15일 = 15일 → 불가).
     *
     * @param today 철회 요청일
     * @throws InvalidPolicyTransitionException 창구 초과 또는 불허용 상태에서 호출 시
     */
    public void cancel(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (status != PolicyStatus.ACTIVE && status != PolicyStatus.LAPSED) {
            throw new InvalidPolicyTransitionException(status, PolicyStatus.CANCELLED);
        }
        long daysSinceEffective = ChronoUnit.DAYS.between(effectiveDate, today);
        if (daysSinceEffective >= CANCELLATION_WINDOW_DAYS) {
            throw new InvalidPolicyTransitionException(
                    "청약철회는 효력일(" + effectiveDate + ")로부터 15일 이내에만 가능합니다. 경과=" + daysSinceEffective + "일");
        }
        transitionTo(PolicyStatus.CANCELLED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 내부 상태 전이 가드
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 상태 전이 내부 가드.
     *
     * <p>{@link PolicyStatus#canTransitionTo} 를 2차 안전망으로 확인한다.
     * 비즈니스 전제조건(납입 실패 횟수·기간 등)은 각 공개 메서드가 먼저 검증한다.
     */
    private void transitionTo(PolicyStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidPolicyTransitionException(status, target);
        }
        this.status = target;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Getters (setter 없음 — guard.mjs OO 규칙)
    // ─────────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public LocalDate getLapsedAt() {
        return lapsedAt;
    }

    public int getConsecutivePremiumFailures() {
        return consecutivePremiumFailures;
    }

    public BigDecimal getPremiumAmount() {
        return premiumAmount;
    }

    public String getFcId() {
        return fcId;
    }

    public SalesChannel getSalesChannel() {
        return salesChannel;
    }

    public String getPartnerBankCode() {
        return partnerBankCode;
    }

    /**
     * 이 계약의 수수료 수령 주체 식별자 — FC 채널은 설계사, BANCA 채널은 판매 은행.
     */
    public String commissionRecipientId() {
        return salesChannel == SalesChannel.BANCA ? partnerBankCode : fcId;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Builder (영속성 어댑터 재구성 + 정적 팩토리 공용)
    // ─────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String policyId;
        private String policyNumber;
        private PolicyStatus status;
        private LocalDate effectiveDate;
        private LocalDate maturityDate;
        private LocalDate lapsedAt;
        private int consecutivePremiumFailures;
        private BigDecimal premiumAmount;
        private String fcId;
        private SalesChannel salesChannel;
        private String partnerBankCode;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder policyId(String v) {
            this.policyId = v;
            return this;
        }

        public Builder policyNumber(String v) {
            this.policyNumber = v;
            return this;
        }

        public Builder status(PolicyStatus v) {
            this.status = v;
            return this;
        }

        public Builder effectiveDate(LocalDate v) {
            this.effectiveDate = v;
            return this;
        }

        public Builder maturityDate(LocalDate v) {
            this.maturityDate = v;
            return this;
        }

        public Builder lapsedAt(LocalDate v) {
            this.lapsedAt = v;
            return this;
        }

        public Builder consecutivePremiumFailures(int v) {
            this.consecutivePremiumFailures = v;
            return this;
        }

        public Builder premiumAmount(BigDecimal v) {
            this.premiumAmount = v;
            return this;
        }

        public Builder fcId(String v) {
            this.fcId = v;
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

        public Policy build() {
            return new Policy(this);
        }
    }
}
