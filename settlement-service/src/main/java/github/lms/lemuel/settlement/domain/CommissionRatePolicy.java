package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 유효기간이 있는 수수료율 정책 (ADR 0032).
 *
 * <p>요율을 enum 상수가 아니라 <b>기간을 가진 데이터</b>로 다룬다 — "9월부터 2.3%", "이 셀러는 계약상
 * 1.8%", "블프 2주 한시 인하"를 배포 없이 표현하기 위함이다.
 *
 * <p>유효기간은 <b>{@code [from, to)} 반열림</b>이다. 종료일 당일은 이미 다음 정책의 몫이라, 정책을
 * 이어 붙일 때 하루가 겹치거나 비지 않는다.
 *
 * <p>행 UPDATE 는 금지다 — 요율을 바꾸려면 기존 행을 닫고 새 행을 넣는다(원장 POSTED 불변과 같은 규율).
 * 그래서 이 테이블 자체가 곧 이력이다.
 */
public record CommissionRatePolicy(Long id, RateScope scope, String scopeKey, BigDecimal rate,
                                   LocalDate effectiveFrom, LocalDate effectiveTo) {

    public static CommissionRatePolicy rehydrate(Long id, RateScope scope, String scopeKey, BigDecimal rate,
                                                 LocalDate effectiveFrom, LocalDate effectiveTo) {
        return new CommissionRatePolicy(id, scope, scopeKey, rate, effectiveFrom, effectiveTo);
    }

    /** {@code at} 시점에 유효한지 — 발효일 포함, 종료일 제외. */
    public boolean isEffectiveOn(LocalDate at) {
        if (at == null || effectiveFrom == null || at.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || at.isBefore(effectiveTo);
    }

    /** 이 정책이 해당 셀러·등급에 적용되는지. */
    public boolean appliesTo(SellerTier tier, Long sellerId) {
        return switch (scope) {
            case SELLER -> sellerId != null && String.valueOf(sellerId).equals(scopeKey);
            case TIER -> tier != null && tier.name().equals(scopeKey);
        };
    }

    /**
     * 후보 정책들에서 적용 요율 하나를 고른다 — <b>DB·시계 접근 없는 순수 함수</b>.
     *
     * <p>우선순위는 <b>SELLER > TIER</b>: 가장 구체적인 계약이 이긴다. 같은 scope 안의 기간 중첩은
     * DB {@code EXCLUDE} 제약이 입력 시점에 막으므로 여기서 다투지 않는다.
     *
     * <p><b>폴백이 항상 존재한다</b> — 매칭 0건이면 등급 기본율({@link SellerTier#rate()})이다.
     * 즉 정책 테이블이 비어 있으면 도입 전과 100% 동일하게 동작한다(무행동 착지).
     */
    public static ResolvedRate resolve(List<CommissionRatePolicy> candidates, SellerTier tier,
                                       Long sellerId, LocalDate at) {
        SellerTier effectiveTier = tier != null ? tier : SellerTier.NORMAL;

        return (candidates == null ? List.<CommissionRatePolicy>of() : candidates).stream()
                .filter(p -> p.isEffectiveOn(at))
                .filter(p -> p.appliesTo(effectiveTier, sellerId))
                // SELLER 를 TIER 보다 앞세운다(ordinal: SELLER=0, TIER=1).
                .min(Comparator.comparingInt(p -> p.scope().ordinal()))
                .map(p -> new ResolvedRate(p.rate(), RateSource.valueOf(p.scope().name()), p.scopeKey()))
                .orElseGet(() -> new ResolvedRate(effectiveTier.rate(), RateSource.DEFAULT_TIER,
                        effectiveTier.name()));
    }
}
