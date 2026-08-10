package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;

/**
 * 해석된 요율과 그 근거.
 *
 * @param sourceKey 근거 식별자(예: 셀러 12345, 등급 VIP). 감사·문의 대응용
 */
public record ResolvedRate(BigDecimal rate, RateSource source, String sourceKey) {

    /** {@code settlements.commission_rate_source} 에 저장할 표기 — 예: {@code SELLER:12345}. */
    public String sourceLabel() {
        return source == RateSource.DEFAULT_TIER ? source.name() : source.name() + ":" + sourceKey;
    }
}
