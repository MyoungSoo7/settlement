package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ImprovementAdvicePolicy — 지표별 다음 구간 유도·null(미공시) 처리·최상 구간 무조언 검증
 * + InvestmentScorePolicy 구간표와의 드리프트 교차검증.
 */
class ImprovementAdvicePolicyTest {

    private final ImprovementAdvicePolicy policy = new ImprovementAdvicePolicy();

    private static InvestmentScore score(InvestmentScore.Profitability p,
                                         InvestmentScore.Stability s,
                                         InvestmentScore.Growth g) {
        int total = p.score() + s.score() + g.score();
        return new InvestmentScore("005930", "삼성전자", "KOSPI", 2025, total,
                InvestmentGrade.fromScore(total), total >= 60, p, s, g);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("각 지표가 다음 구간 임계값·상승폭으로 유도된다 (수익성→안정성→성장성 순)")
    void derivesNextBandAdvice() {
        List<ImprovementAdvice> advices = policy.advise(score(
                new InvestmentScore.Profitability(18, bd("10.88"), bd("6.7")),   // margin 12점→15% +4 / roa 6점→7% +3
                new InvestmentScore.Stability(28, bd("66.6"), bd("55.0")),       // debt 16점→50% +4 / equity 12점→60% +3
                new InvestmentScore.Growth(21, bd("16.2"), bd("8.0"))));         // rev 12점→20% +3 / ni 9점→10% +3

        assertThat(advices).hasSize(6);
        assertThat(advices).extracting(ImprovementAdvice::metric).containsExactly(
                "operatingMargin", "roa", "debtRatio", "equityRatio", "revenueGrowth", "netIncomeGrowth");
        assertThat(advices).extracting(ImprovementAdvice::potentialGain).containsExactly(4, 3, 4, 3, 3, 3);
        assertThat(advices.get(0).message()).contains("10.88%").contains("15%").contains("+4점");
        assertThat(advices.get(2).message()).contains("66.6%").contains("50%").contains("낮추면");
        assertThat(advices.get(0).axis()).isEqualTo(ImprovementAdvice.Axis.PROFITABILITY);
        assertThat(advices.get(2).axis()).isEqualTo(ImprovementAdvice.Axis.STABILITY);
        assertThat(advices.get(4).axis()).isEqualTo(ImprovementAdvice.Axis.GROWTH);
    }

    @Test
    @DisplayName("전 지표 최상 구간이면 개선 포인트가 없다")
    void emptyWhenAllTopBand() {
        List<ImprovementAdvice> advices = policy.advise(score(
                new InvestmentScore.Profitability(35, bd("25"), bd("16")),
                new InvestmentScore.Stability(35, bd("40"), bd("70")),
                new InvestmentScore.Growth(30, bd("25"), bd("30"))));

        assertThat(advices).isEmpty();
    }

    @Test
    @DisplayName("지표 null(계정 미공시)이면 공시 보완이 개선 포인트가 된다 — 상승폭은 해당 지표 만점")
    void nullMetricAdvisesDisclosure() {
        List<ImprovementAdvice> advices = policy.advise(score(
                new InvestmentScore.Profitability(0, null, null),
                new InvestmentScore.Stability(0, null, null),
                new InvestmentScore.Growth(30, bd("25"), bd("30"))));

        assertThat(advices).hasSize(4);
        assertThat(advices).extracting(ImprovementAdvice::potentialGain).containsExactly(20, 15, 20, 15);
        assertThat(advices).allSatisfy(a -> assertThat(a.message()).contains("산정 불가"));
    }

    @Test
    @DisplayName("직전 연도 부재(성장성 중립 15점)면 '연속 공시 확보' 단일 조언 +15점")
    void neutralGrowthAdvisesHistory() {
        List<ImprovementAdvice> advices = policy.advise(score(
                new InvestmentScore.Profitability(35, bd("25"), bd("16")),
                new InvestmentScore.Stability(35, bd("40"), bd("70")),
                new InvestmentScore.Growth(15, null, null)));

        assertThat(advices).hasSize(1);
        assertThat(advices.get(0).metric()).isEqualTo("growthHistory");
        assertThat(advices.get(0).potentialGain()).isEqualTo(15);
        assertThat(advices.get(0).message()).contains("연속 공시");
    }

    @Test
    @DisplayName("부채비율 300% 초과(0점 구간)는 300% 이하 진입이 첫 개선 포인트 +4점")
    void debtRatioWorstBand() {
        List<ImprovementAdvice> advices = policy.advise(score(
                new InvestmentScore.Profitability(35, bd("25"), bd("16")),
                new InvestmentScore.Stability(3, bd("350"), bd("20")),
                new InvestmentScore.Growth(30, bd("25"), bd("30"))));

        ImprovementAdvice debt = advices.stream()
                .filter(a -> a.metric().equals("debtRatio")).findFirst().orElseThrow();
        assertThat(debt.potentialGain()).isEqualTo(4);
        assertThat(debt.message()).contains("300%");
    }

    @Test
    @DisplayName("음수 성장률(-15%)은 -10% 구간 진입이 다음 개선 포인트 +3점")
    void negativeGrowthAdvisesNextBand() {
        List<ImprovementAdvice> advices = policy.advise(score(
                new InvestmentScore.Profitability(35, bd("25"), bd("16")),
                new InvestmentScore.Stability(35, bd("40"), bd("70")),
                new InvestmentScore.Growth(15, bd("-15"), bd("25"))));

        assertThat(advices).hasSize(1);
        assertThat(advices.get(0).metric()).isEqualTo("revenueGrowth");
        assertThat(advices.get(0).potentialGain()).isEqualTo(3);
        assertThat(advices.get(0).message()).contains("-10%");
    }

    // ─── 드리프트 가드 — InvestmentScorePolicy 와 구간표 교차검증 ───────────────────────

    @Test
    @DisplayName("조언대로 지표를 개선하면 InvestmentScorePolicy 점수가 정확히 상승폭만큼 오른다 (드리프트 가드)")
    void adviceGainMatchesScorePolicy() {
        InvestmentScorePolicy scorePolicy = new InvestmentScorePolicy();

        // 영업이익률 10.88% (그 외 지표 null) 기준 점수
        InvestmentScore before = scorePolicy.score(financialsWithMargin("10.88"));
        List<ImprovementAdvice> advices = policy.advise(before);
        ImprovementAdvice marginAdvice = advices.stream()
                .filter(a -> a.metric().equals("operatingMargin")).findFirst().orElseThrow();

        // 조언 임계값(15%)에 도달시킨 뒤 재채점 → 수익성 점수가 정확히 potentialGain 만큼 상승해야 한다
        InvestmentScore after = scorePolicy.score(financialsWithMargin("15"));
        assertThat(after.profitability().score() - before.profitability().score())
                .isEqualTo(marginAdvice.potentialGain());
    }

    private static CompanyFinancials financialsWithMargin(String operatingMargin) {
        return new CompanyFinancials("005930", "삼성전자", "KOSPI", List.of(new AnnualStatement(
                2025, null, null, null, null, null, null,
                new BigDecimal(operatingMargin), null, null, null, null)));
    }
}
