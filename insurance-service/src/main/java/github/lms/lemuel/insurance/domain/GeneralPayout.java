package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutException;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutTransitionException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 일반지급(GeneralPayout) 애그리거트 — 순수 POJO, 프레임워크 의존 0.
 *
 * <p><b>D-G1</b>: 일반지급은 청구 접수가 아니라 Policy 의 terminal 전이(해지·만기·철회)가
 * 낳는다 — 심사 단계가 없다(지급 사유·금액이 전이 시점에 확정).
 *
 * <p><b>D-G4 — 상태머신</b>: REQUESTED → PAID 1개 전이만 허용.
 * 비허용 전이는 {@link InvalidGeneralPayoutTransitionException} — 조용한 무시 금지.
 *
 * <p><b>D-G5 — 산출근거 스냅샷</b>: 기납입합계·적용요율·경과월수·납입회차수를 생성 시점에
 * 고정 보존한다. 지급액이 어떻게 나왔는지 행 하나로 재구성 가능해야 한다.
 *
 * <p><b>멱등</b>: payoutId(UUID, L1) + V10 의 {@code UNIQUE(policy_id, payout_type)}(L3) —
 * terminal 상태가 상호배타라 계약당 유형별 1건이 자연키다.
 */
public class GeneralPayout {

    private Long id;
    private final String payoutId;              // UUID — 멱등성 L1
    private final String policyId;              // UUID — 대상 계약
    private final String policyNumber;          // 증권번호 — 이벤트 파티션 키
    private final GeneralPayoutType payoutType;
    private final BigDecimal amount;            // 지급액 (> 0 — 0원 payout 은 존재하지 않는다)
    private final BigDecimal paidPremiumTotal;  // D-G5: 기납입보험료 합계 스냅샷
    private final BigDecimal appliedRate;       // D-G5: 적용 환급률 스냅샷
    private final long elapsedMonths;           // D-G5: 경과월수 스냅샷
    private final int installmentCount;         // D-G5: 납입 회차 수 스냅샷
    private GeneralPayoutStatus status;
    private final LocalDate requestedOn;        // 지급 요청일 (전이 발생일)
    private LocalDate paidOn;                   // 지급일 (nullable)

    private GeneralPayout(Builder b) {
        this.id               = b.id;
        this.payoutId         = Objects.requireNonNull(b.payoutId, "payoutId");
        this.policyId         = Objects.requireNonNull(b.policyId, "policyId");
        this.policyNumber     = Objects.requireNonNull(b.policyNumber, "policyNumber");
        this.payoutType       = Objects.requireNonNull(b.payoutType, "payoutType");
        this.amount           = Objects.requireNonNull(b.amount, "amount");
        this.paidPremiumTotal = Objects.requireNonNull(b.paidPremiumTotal, "paidPremiumTotal");
        this.appliedRate      = Objects.requireNonNull(b.appliedRate, "appliedRate");
        this.elapsedMonths    = b.elapsedMonths;
        this.installmentCount = b.installmentCount;
        this.status           = b.status != null ? b.status : GeneralPayoutStatus.REQUESTED;
        this.requestedOn      = Objects.requireNonNull(b.requestedOn, "requestedOn");
        this.paidOn           = b.paidOn;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 생성 팩토리 — Policy terminal 전이 경로 전용
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 지급 요청 생성 — Policy terminal 전이 시점에 산출 결과로부터 태어난다.
     *
     * @param quote 산출 결과 — 지급액이 0 이하면 생성 자체가 거부된다
     *              (0원 지급 행은 존재하지 않는다, D-G3)
     * @throws InvalidGeneralPayoutException 지급액이 0 이하이면
     */
    public static GeneralPayout request(String policyId, String policyNumber,
                                        GeneralPayoutType payoutType, PayoutQuote quote,
                                        LocalDate requestedOn) {
        Objects.requireNonNull(quote, "quote");
        if (quote.amount().signum() <= 0) {
            throw new InvalidGeneralPayoutException(
                    "0원 이하 일반지급은 생성할 수 없습니다: amount=" + quote.amount().toPlainString());
        }
        return builder()
                .payoutId(UUID.randomUUID().toString())
                .policyId(policyId)
                .policyNumber(policyNumber)
                .payoutType(payoutType)
                .amount(quote.amount())
                .paidPremiumTotal(quote.paidPremiumTotal())
                .appliedRate(quote.appliedRate())
                .elapsedMonths(quote.elapsedMonths())
                .installmentCount(quote.installmentCount())
                .status(GeneralPayoutStatus.REQUESTED)
                .requestedOn(requestedOn)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // D-G4 상태 전이 메서드
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 지급 — REQUESTED → PAID.
     *
     * <p>일반지급 실행 배치가 호출한다. 지급액은 amount 전액 — 부분 지급 없음.
     *
     * @param paidOn 지급일
     * @throws InvalidGeneralPayoutTransitionException 이미 지급된 건이면
     */
    public void markPaid(LocalDate paidOn) {
        Objects.requireNonNull(paidOn, "paidOn");
        transitionTo(GeneralPayoutStatus.PAID);
        this.paidOn = paidOn;
    }

    private void transitionTo(GeneralPayoutStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidGeneralPayoutTransitionException(status, target);
        }
        this.status = target;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Getters (setter 없음 — guard.mjs OO 규칙)
    // ─────────────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getPayoutId() {
        return payoutId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public GeneralPayoutType getPayoutType() {
        return payoutType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPaidPremiumTotal() {
        return paidPremiumTotal;
    }

    public BigDecimal getAppliedRate() {
        return appliedRate;
    }

    public long getElapsedMonths() {
        return elapsedMonths;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public GeneralPayoutStatus getStatus() {
        return status;
    }

    public LocalDate getRequestedOn() {
        return requestedOn;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Builder (영속성 어댑터 재구성 + 정적 팩토리 공용)
    // ─────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String payoutId;
        private String policyId;
        private String policyNumber;
        private GeneralPayoutType payoutType;
        private BigDecimal amount;
        private BigDecimal paidPremiumTotal;
        private BigDecimal appliedRate;
        private long elapsedMonths;
        private int installmentCount;
        private GeneralPayoutStatus status;
        private LocalDate requestedOn;
        private LocalDate paidOn;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder payoutId(String v) {
            this.payoutId = v;
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

        public Builder payoutType(GeneralPayoutType v) {
            this.payoutType = v;
            return this;
        }

        public Builder amount(BigDecimal v) {
            this.amount = v;
            return this;
        }

        public Builder paidPremiumTotal(BigDecimal v) {
            this.paidPremiumTotal = v;
            return this;
        }

        public Builder appliedRate(BigDecimal v) {
            this.appliedRate = v;
            return this;
        }

        public Builder elapsedMonths(long v) {
            this.elapsedMonths = v;
            return this;
        }

        public Builder installmentCount(int v) {
            this.installmentCount = v;
            return this;
        }

        public Builder status(GeneralPayoutStatus v) {
            this.status = v;
            return this;
        }

        public Builder requestedOn(LocalDate v) {
            this.requestedOn = v;
            return this;
        }

        public Builder paidOn(LocalDate v) {
            this.paidOn = v;
            return this;
        }

        public GeneralPayout build() {
            return new GeneralPayout(this);
        }
    }
}
