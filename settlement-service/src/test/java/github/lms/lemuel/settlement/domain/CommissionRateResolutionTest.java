package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수수료율 해석 — 순수 함수(ADR 0032 §2).
 *
 * <p>정책 테이블이 비면 <b>오늘과 100% 동일하게</b> 동작해야 한다(enum 폴백). 이 성질이 무행동 착지를
 * 가능하게 하고, 정책 도입이 기존 정산 금액을 조용히 바꾸지 않는다는 보장이기도 하다.
 */
class CommissionRateResolutionTest {

    private static final LocalDate AT = LocalDate.of(2026, 9, 15);

    private CommissionRatePolicy policy(RateScope scope, String key, String rate,
                                        LocalDate from, LocalDate to) {
        return CommissionRatePolicy.rehydrate(1L, scope, key, new BigDecimal(rate), from, to);
    }

    @Test @DisplayName("정책이 없으면 등급 기본 요율로 폴백한다 — 도입 전과 동일 동작")
    void emptyPolicies_fallsBackToTierRate() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(List.of(), SellerTier.VIP, 12345L, AT);

        assertThat(resolved.rate()).isEqualByComparingTo(SellerTier.VIP.rate());
        assertThat(resolved.source()).isEqualTo(RateSource.DEFAULT_TIER);
    }

    @Test @DisplayName("TIER 정책이 있으면 등급 기본값을 덮는다")
    void tierPolicyOverridesEnum() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "VIP", "0.02300", AT.minusDays(30), null)),
                SellerTier.VIP, 12345L, AT);

        assertThat(resolved.rate()).isEqualByComparingTo("0.02300");
        assertThat(resolved.source()).isEqualTo(RateSource.TIER);
    }

    @Test @DisplayName("SELLER 계약이 TIER 정책을 이긴다 — 가장 구체적인 계약이 우선")
    void sellerBeatsTier() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(List.of(
                        policy(RateScope.TIER, "VIP", "0.02300", AT.minusDays(30), null),
                        policy(RateScope.SELLER, "12345", "0.01800", AT.minusDays(30), null)),
                SellerTier.VIP, 12345L, AT);

        assertThat(resolved.rate()).isEqualByComparingTo("0.01800");
        assertThat(resolved.source()).isEqualTo(RateSource.SELLER);
    }

    @Test @DisplayName("다른 셀러의 계약은 나에게 적용되지 않는다")
    void otherSellersContractIsIgnored() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(
                List.of(policy(RateScope.SELLER, "99999", "0.01000", AT.minusDays(30), null)),
                SellerTier.VIP, 12345L, AT);

        assertThat(resolved.source()).isEqualTo(RateSource.DEFAULT_TIER);
    }

    @Test @DisplayName("다른 등급의 정책은 적용되지 않는다")
    void otherTiersPolicyIsIgnored() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "STRATEGIC", "0.01000", AT.minusDays(30), null)),
                SellerTier.VIP, 12345L, AT);

        assertThat(resolved.source()).isEqualTo(RateSource.DEFAULT_TIER);
    }

    // ── 유효기간 경계 — [from, to) 반열림 ──

    @Test @DisplayName("발효일 당일부터 적용된다")
    void effectiveFromIsInclusive() {
        assertThat(CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "VIP", "0.02300", AT, null)),
                SellerTier.VIP, 1L, AT).source()).isEqualTo(RateSource.TIER);
    }

    @Test @DisplayName("발효일 전날은 적용되지 않는다")
    void beforeEffectiveFromIsNotApplied() {
        assertThat(CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "VIP", "0.02300", AT.plusDays(1), null)),
                SellerTier.VIP, 1L, AT).source()).isEqualTo(RateSource.DEFAULT_TIER);
    }

    @Test @DisplayName("종료일 당일은 이미 만료다 — 반열림이라 그날은 새 정책의 몫")
    void effectiveToIsExclusive() {
        assertThat(CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "VIP", "0.02300", AT.minusDays(10), AT)),
                SellerTier.VIP, 1L, AT).source()).isEqualTo(RateSource.DEFAULT_TIER);
    }

    @Test @DisplayName("종료일 하루 전까지는 적용된다")
    void dayBeforeEffectiveToIsApplied() {
        assertThat(CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "VIP", "0.02300", AT.minusDays(10), AT.plusDays(1))),
                SellerTier.VIP, 1L, AT).source()).isEqualTo(RateSource.TIER);
    }

    @Test @DisplayName("종료일이 없으면 무기한 유효")
    void nullEffectiveToIsOpenEnded() {
        assertThat(CommissionRatePolicy.resolve(
                List.of(policy(RateScope.TIER, "VIP", "0.02300", AT.minusYears(5), null)),
                SellerTier.VIP, 1L, AT).source()).isEqualTo(RateSource.TIER);
    }

    // ── 방어 ──

    @Test @DisplayName("해석 결과는 항상 존재한다 — null 반환 경로가 없다")
    void alwaysResolves() {
        for (SellerTier tier : SellerTier.values()) {
            assertThat(CommissionRatePolicy.resolve(List.of(), tier, 1L, AT)).isNotNull();
        }
    }

    @Test @DisplayName("등급이 없으면 NORMAL 기본율로 해석한다")
    void nullTierFallsBackToNormal() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(List.of(), null, 1L, AT);

        assertThat(resolved.rate()).isEqualByComparingTo(SellerTier.NORMAL.rate());
    }

    @Test @DisplayName("셀러가 미상이면 SELLER 계약은 적용될 수 없다")
    void nullSellerCannotMatchSellerScope() {
        ResolvedRate resolved = CommissionRatePolicy.resolve(
                List.of(policy(RateScope.SELLER, "12345", "0.01800", AT.minusDays(30), null)),
                SellerTier.VIP, null, AT);

        assertThat(resolved.source()).isEqualTo(RateSource.DEFAULT_TIER);
    }
}
