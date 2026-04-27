package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;

/**
 * 판매자 등급별 수수료율.
 *
 * <p>프로젝트 전체 기본(레거시)은 3% — {@link Settlement#COMMISSION_RATE} 상수로 보존.
 * 차등 수수료 전환 후에는 각 정산이 등급 기준 rate 를 스냅샷 저장한다 (V32 {@code commission_rate} 컬럼).
 */
public enum SellerTier {
    NORMAL("0.0350"),       // 기본 3.5%
    VIP("0.0250"),          // 2.5%
    STRATEGIC("0.0200");    // 2.0% — 대형 전략 파트너

    private final BigDecimal rate;

    SellerTier(String rate) {
        this.rate = new BigDecimal(rate);
    }

    public BigDecimal rate() {
        return rate;
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
