package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;

/**
 * 판매자 등급별 수수료율.
 *
 * <p>프로젝트 전체 기본(레거시)은 3% — {@link Settlement#COMMISSION_RATE} 상수로 보존.
 * 차등 수수료 전환 후에는 각 정산이 등급 기준 rate 를 스냅샷 저장한다 (V32 {@code commission_rate} 컬럼).
 */
public enum SellerTier {
    /** 기본 등급 — 3.5% 수수료, T+7 영업일 정산. */
    NORMAL("0.0350", SettlementCycle.T_PLUS_7, "0.30", 30),
    /** VIP — 2.5% 수수료, T+3 영업일 정산. */
    VIP("0.0250", SettlementCycle.T_PLUS_3, "0.10", 14),
    /** STRATEGIC — 2.0% 수수료, T+1 영업일 정산 (대형 전략 파트너). */
    STRATEGIC("0.0200", SettlementCycle.T_PLUS_1, "0", 0);

    private final BigDecimal rate;
    private final SettlementCycle defaultCycle;
    private final BigDecimal holdbackRate;
    private final int holdbackReleaseDays;

    SellerTier(String rate, SettlementCycle defaultCycle, String holdbackRate, int holdbackReleaseDays) {
        this.rate = new BigDecimal(rate);
        this.defaultCycle = defaultCycle;
        this.holdbackRate = new BigDecimal(holdbackRate);
        this.holdbackReleaseDays = holdbackReleaseDays;
    }

    public BigDecimal rate() {
        return rate;
    }

    /**
     * 등급별 기본 정산 주기. {@code users.settlement_cycle} 컬럼이 명시적으로 다른 값을
     * 가지면 그것이 우선이지만, 미지정 시 이 default 가 적용된다.
     */
    public SettlementCycle defaultCycle() {
        return defaultCycle;
    }

    /** 등급별 기본 홀드백(보류) 비율 — NORMAL 30%·VIP 10%·STRATEGIC 0%. */
    public BigDecimal holdbackRate() {
        return holdbackRate;
    }

    /** 등급별 기본 홀드백 해제 기간(영업일) — NORMAL 30·VIP 14·STRATEGIC 0. */
    public int holdbackReleaseDays() {
        return holdbackReleaseDays;
    }

    public static SellerTier fromStringOrDefault(String value) {
        if (value == null || value.isBlank()) return NORMAL;
        try {
            return SellerTier.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
