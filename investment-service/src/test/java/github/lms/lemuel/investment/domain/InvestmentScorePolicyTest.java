package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentScorePolicyTest {

    private final InvestmentScorePolicy policy = new InvestmentScorePolicy();

    private static BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    /** operatingMargin·roa 만 지정한 단일 연도 재무제표(직전 연도 없음 → 성장성 중립 15). */
    private InvestmentScore profitabilityScore(String opMargin, String roa) {
        AnnualStatement s = new AnnualStatement(2024, null, null, null, null, null, null,
                bd(opMargin), null, null, null, bd(roa));
        return policy.score(new CompanyFinancials("005930", "샘플", "KOSPI", List.of(s)));
    }

    private InvestmentScore stabilityScore(String debtRatio, String equityRatio) {
        AnnualStatement s = new AnnualStatement(2024, null, null, null, null, null, null,
                null, null, bd(debtRatio), bd(equityRatio), null);
        return policy.score(new CompanyFinancials("005930", "샘플", "KOSPI", List.of(s)));
    }

    private InvestmentScore growthScore(String currRev, String prevRev, String currNi, String prevNi) {
        AnnualStatement latest = new AnnualStatement(2024, bd(currRev), null, bd(currNi),
                null, null, null, null, null, null, null, null);
        AnnualStatement prev = new AnnualStatement(2023, bd(prevRev), null, bd(prevNi),
                null, null, null, null, null, null, null, null);
        return policy.score(new CompanyFinancials("005930", "샘플", "KOSPI", List.of(latest, prev)));
    }

    // ─── 수익성 ──────────────────────────────────────────────────────────────────

    @Test
    void 영업이익률_구간_경계값() {
        assertThat(profitabilityScore("20", null).profitability().score()).isEqualTo(20);
        assertThat(profitabilityScore("19.99", null).profitability().score()).isEqualTo(16);
        assertThat(profitabilityScore("15", null).profitability().score()).isEqualTo(16);
        assertThat(profitabilityScore("14.99", null).profitability().score()).isEqualTo(12);
        assertThat(profitabilityScore("10", null).profitability().score()).isEqualTo(12);
        assertThat(profitabilityScore("9.99", null).profitability().score()).isEqualTo(8);
        assertThat(profitabilityScore("5", null).profitability().score()).isEqualTo(8);
        assertThat(profitabilityScore("4.99", null).profitability().score()).isEqualTo(4);
        assertThat(profitabilityScore("0", null).profitability().score()).isEqualTo(4);
        assertThat(profitabilityScore("-0.01", null).profitability().score()).isEqualTo(0);
        assertThat(profitabilityScore(null, null).profitability().score()).isEqualTo(0);
    }

    @Test
    void ROA_구간_경계값() {
        assertThat(profitabilityScore(null, "15").profitability().score()).isEqualTo(15);
        assertThat(profitabilityScore(null, "14.99").profitability().score()).isEqualTo(12);
        assertThat(profitabilityScore(null, "10").profitability().score()).isEqualTo(12);
        assertThat(profitabilityScore(null, "9.99").profitability().score()).isEqualTo(9);
        assertThat(profitabilityScore(null, "7").profitability().score()).isEqualTo(9);
        assertThat(profitabilityScore(null, "6.99").profitability().score()).isEqualTo(6);
        assertThat(profitabilityScore(null, "4").profitability().score()).isEqualTo(6);
        assertThat(profitabilityScore(null, "3.99").profitability().score()).isEqualTo(3);
        assertThat(profitabilityScore(null, "0").profitability().score()).isEqualTo(3);
        assertThat(profitabilityScore(null, "-0.01").profitability().score()).isEqualTo(0);
    }

    @Test
    void 개별_지표_null_은_해당_밴드만_0점이고_나머지는_정상산정() {
        // roa 만 null → operatingMargin 20점 유지, roa 밴드만 0
        assertThat(profitabilityScore("20", null).profitability().score()).isEqualTo(20);
        // operatingMargin 만 null → roa 15점 유지, margin 밴드만 0
        assertThat(profitabilityScore(null, "15").profitability().score()).isEqualTo(15);
        // equityRatio 만 null → debtRatio 20점 유지, equity 밴드만 0
        assertThat(stabilityScore("50", null).stability().score()).isEqualTo(20);
        // debtRatio 만 null → equityRatio 15점 유지, debt 밴드만 0
        assertThat(stabilityScore(null, "60").stability().score()).isEqualTo(15);
    }

    @Test
    void 부채비율_AT_MOST_최저구간은_음수도_경계이하로_20점() {
        // 낮을수록 고득점(AT_MOST) 방향에서 경계값보다 훨씬 낮은 값(0·음수)도 최고 밴드에 매칭.
        assertThat(stabilityScore("0", null).stability().score()).isEqualTo(20);
        assertThat(stabilityScore("-5", null).stability().score()).isEqualTo(20);
    }

    @Test
    void 수익성_최고점은_35이고_근거지표를_보존한다() {
        InvestmentScore s = profitabilityScore("25", "20");
        assertThat(s.profitability().score()).isEqualTo(35);
        assertThat(s.profitability().operatingMargin()).isEqualByComparingTo("25");
        assertThat(s.profitability().roa()).isEqualByComparingTo("20");
    }

    // ─── 안정성 ──────────────────────────────────────────────────────────────────

    @Test
    void 부채비율_구간_경계값_낮을수록_고득점() {
        assertThat(stabilityScore("50", null).stability().score()).isEqualTo(20);
        assertThat(stabilityScore("50.01", null).stability().score()).isEqualTo(16);
        assertThat(stabilityScore("100", null).stability().score()).isEqualTo(16);
        assertThat(stabilityScore("100.01", null).stability().score()).isEqualTo(12);
        assertThat(stabilityScore("150", null).stability().score()).isEqualTo(12);
        assertThat(stabilityScore("150.01", null).stability().score()).isEqualTo(8);
        assertThat(stabilityScore("200", null).stability().score()).isEqualTo(8);
        assertThat(stabilityScore("200.01", null).stability().score()).isEqualTo(4);
        assertThat(stabilityScore("300", null).stability().score()).isEqualTo(4);
        assertThat(stabilityScore("300.01", null).stability().score()).isEqualTo(0);
        assertThat(stabilityScore(null, null).stability().score()).isEqualTo(0);
    }

    @Test
    void 자기자본비율_구간_경계값() {
        assertThat(stabilityScore(null, "60").stability().score()).isEqualTo(15);
        assertThat(stabilityScore(null, "59.99").stability().score()).isEqualTo(12);
        assertThat(stabilityScore(null, "50").stability().score()).isEqualTo(12);
        assertThat(stabilityScore(null, "49.99").stability().score()).isEqualTo(9);
        assertThat(stabilityScore(null, "40").stability().score()).isEqualTo(9);
        assertThat(stabilityScore(null, "39.99").stability().score()).isEqualTo(6);
        assertThat(stabilityScore(null, "30").stability().score()).isEqualTo(6);
        assertThat(stabilityScore(null, "29.99").stability().score()).isEqualTo(3);
        assertThat(stabilityScore(null, "20").stability().score()).isEqualTo(3);
        assertThat(stabilityScore(null, "19.99").stability().score()).isEqualTo(0);
    }

    // ─── 성장성 ──────────────────────────────────────────────────────────────────

    @Test
    void 직전연도가_없으면_성장성은_중립_15점이고_근거는_null() {
        InvestmentScore s = profitabilityScore("10", "10");
        assertThat(s.growth().score()).isEqualTo(15);
        assertThat(s.growth().revenueGrowth()).isNull();
        assertThat(s.growth().netIncomeGrowth()).isNull();
    }

    @Test
    void 매출과_순이익_YoY_구간_경계값() {
        // 매출만 변화(순이익은 동일 → 0% → 6점), 매출 성장률 경계 검증
        assertThat(growthScore("120", "100", "100", "100").growth().score()).isEqualTo(15 + 6); // +20% → 15
        assertThat(growthScore("119.99", "100", "100", "100").growth().score()).isEqualTo(12 + 6); // +19.99% → 12
        assertThat(growthScore("110", "100", "100", "100").growth().score()).isEqualTo(12 + 6); // +10% → 12
        assertThat(growthScore("109.99", "100", "100", "100").growth().score()).isEqualTo(9 + 6); // +9.99% → 9
        assertThat(growthScore("105", "100", "100", "100").growth().score()).isEqualTo(9 + 6);  // +5% → 9
        assertThat(growthScore("104.99", "100", "100", "100").growth().score()).isEqualTo(6 + 6); // +4.99% → 6
        assertThat(growthScore("100", "100", "100", "100").growth().score()).isEqualTo(6 + 6);  // 0% → 6
        assertThat(growthScore("95", "100", "100", "100").growth().score()).isEqualTo(3 + 6);   // -5% → 3
        assertThat(growthScore("90", "100", "100", "100").growth().score()).isEqualTo(3 + 6);   // -10% → 3
        assertThat(growthScore("89.99", "100", "100", "100").growth().score()).isEqualTo(0 + 6); // -10.01% → 0
    }

    @Test
    void 성장률_산정불가는_0점_처리된다() {
        // 전기 매출 0(분모 부적격) → revenueGrowth null → 0점, 당기 순이익 null → 0점
        InvestmentScore s = growthScore("100", "0", null, "100");
        assertThat(s.growth().revenueGrowth()).isNull();
        assertThat(s.growth().netIncomeGrowth()).isNull();
        assertThat(s.growth().score()).isEqualTo(0);
    }

    @Test
    void 전기가_음수_순이익이면_해당_성장지표는_0점() {
        // 전기 순이익 음수 → netIncomeGrowth null(분모 부적격) → 0점. 매출 +20% → 15점.
        InvestmentScore s = growthScore("120", "100", "50", "-10");
        assertThat(s.growth().netIncomeGrowth()).isNull();
        assertThat(s.growth().revenueGrowth()).isNotNull();
        assertThat(s.growth().score()).isEqualTo(15);
    }

    // ─── 총점/등급/적격 ───────────────────────────────────────────────────────────

    @Test
    void 만점_시나리오는_총점_100_AAA_적격이다() {
        AnnualStatement latest = new AnnualStatement(2024,
                bd("120"), bd("30"), bd("120"), bd("1000"), bd("300"), bd("700"),
                bd("25"), bd("20"), bd("40"), bd("70"), bd("18"));
        AnnualStatement prev = new AnnualStatement(2023,
                bd("100"), bd("20"), bd("100"), bd("900"), bd("300"), bd("600"),
                bd("20"), bd("15"), bd("50"), bd("65"), bd("15"));
        InvestmentScore s = policy.score(new CompanyFinancials("005930", "만점", "KOSPI", List.of(latest, prev)));

        assertThat(s.totalScore()).isEqualTo(100);
        assertThat(s.grade()).isEqualTo(InvestmentGrade.AAA);
        assertThat(s.investable()).isTrue();
        assertThat(s.fiscalYear()).isEqualTo(2024);
        assertThat(s.stockCode()).isEqualTo("005930");
        assertThat(s.companyName()).isEqualTo("만점");
        assertThat(s.market()).isEqualTo("KOSPI");
    }

    @Test
    void 모든_지표_결측이면_수익성안정성_0_성장성중립_총점_15_CCC_부적격() {
        AnnualStatement s = new AnnualStatement(2024, null, null, null, null, null, null,
                null, null, null, null, null);
        InvestmentScore score = policy.score(new CompanyFinancials("005930", "결측", "KOSDAQ", List.of(s)));

        assertThat(score.totalScore()).isEqualTo(15);
        assertThat(score.grade()).isEqualTo(InvestmentGrade.CCC);
        assertThat(score.investable()).isFalse();
    }

    @Test
    void 적격_경계_60점이면_investable_true() {
        // 수익성 20(opMargin>=20) + 안정성 20(debtRatio<=50) + 성장성 중립 15 = 55 (부적격)
        // 자기자본비율 40 추가(+9) = 64 → 적격
        AnnualStatement s = new AnnualStatement(2024, null, null, null, null, null, null,
                bd("20"), null, bd("50"), bd("40"), bd("15"));
        InvestmentScore score = policy.score(new CompanyFinancials("005930", "경계", "KOSPI", List.of(s)));
        // 수익성 = 20 + 12(roa 15?no roa=15→15) ... roa=15 → 15; 20+15=35; 안정성 20+9=29; 성장 15 = 79
        assertThat(score.investable()).isTrue();
        assertThat(score.totalScore()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void 재무제표가_없으면_예외() {
        assertThatThrownBy(() -> policy.score(new CompanyFinancials("005930", "빈", "KOSPI", List.of())))
                .isInstanceOf(InvestmentInvariantViolationException.class);
        assertThatThrownBy(() -> policy.score(null))
                .isInstanceOf(InvestmentInvariantViolationException.class);
    }
}
