package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationScreeningPolicyTest {

    private final RecommendationScreeningPolicy policy = new RecommendationScreeningPolicy();

    private static final String SECTOR = "전력기기";
    private static final BigDecimal PRICE = new BigDecimal("797000");

    private static InvestmentScore score(int total, InvestmentGrade grade, boolean investable) {
        return new InvestmentScore("267260", "HD현대일렉트릭", "KOSPI", 2025, total, grade, investable,
                new InvestmentScore.Profitability(34, new BigDecimal("24.4"), new BigDecimal("8.0")),
                new InvestmentScore.Stability(30, new BigDecimal("60.0"), new BigDecimal("55.0")),
                new InvestmentScore.Growth(28, new BigDecimal("22.8"), new BigDecimal("18.0")));
    }

    private static PricePositionCheck price(PricePositionCheck.Status status) {
        return new PricePositionCheck(status, LocalDate.of(2026, 7, 15), PRICE, 1, false,
                new BigDecimal("820000"), new BigDecimal("500000"), new BigDecimal("-2.8"), false);
    }

    /** CAUTION — 추격 구간(4일 연속 상승) + 52주 고점 근접(-2.8%). */
    private static PricePositionCheck cautionPrice() {
        return new PricePositionCheck(PricePositionCheck.Status.CAUTION, LocalDate.of(2026, 7, 15), PRICE,
                4, true, new BigDecimal("810000"), new BigDecimal("500000"), new BigDecimal("-2.8"), true);
    }

    private static BeginnerInvestmentCheck check(InvestmentScore score, NewsRiskCheck news,
                                                 PricePositionCheck price) {
        TradePlan plan = new TradePlanPolicy().plan(PRICE, null);
        return new BeginnerInvestmentCheck("267260", score, news, price, MacroCheck.of(List.of()), plan);
    }

    private static BeginnerInvestmentCheck passing() {
        return check(score(82, InvestmentGrade.AA, true),
                NewsRiskCheck.of(12, List.of()), price(PricePositionCheck.Status.OK));
    }

    @Test
    @DisplayName("규칙 5종 통과 — 후보 생성, 가격·점수·이유 채워짐")
    void passes() {
        Optional<ScreenedPick> pick = policy.evaluate(passing(), SECTOR);

        assertThat(pick).isPresent();
        ScreenedPick p = pick.get();
        assertThat(p.stockCode()).isEqualTo("267260");
        assertThat(p.stockName()).isEqualTo("HD현대일렉트릭");
        assertThat(p.sector()).isEqualTo(SECTOR);
        assertThat(p.score()).isEqualTo(82);
        // 1차매수 = 현재가(틱내림), 손절 = 평단×0.93, 익절 = 평단×1.20 (평단 = 30/30/40 가중)
        assertThat(p.entryPrice()).isEqualByComparingTo("797000");
        assertThat(p.stopLossPrice()).isEqualByComparingTo("700000");
        assertThat(p.takeProfitPrice()).isEqualByComparingTo("903000");
        assertThat(p.stopLossPrice()).isLessThan(p.entryPrice());
        assertThat(p.entryPrice()).isLessThan(p.takeProfitPrice());
        assertThat(p.reason())
                .contains("규칙 통과")
                .contains("투자점수 82/100(AA)")
                .contains("영업이익률 24.4%")
                .contains("매출성장 +22.8%")
                .contains("최근 뉴스 12건")
                .doesNotContain("시세 주의");   // OK 는 주의 문구 없음
    }

    @Test
    @DisplayName("재무 부적격(investable=false) — 탈락")
    void notInvestable() {
        assertThat(policy.evaluate(check(score(52, InvestmentGrade.BB, false),
                NewsRiskCheck.of(12, List.of()), price(PricePositionCheck.Status.OK)), SECTOR)).isEmpty();
    }

    @Test
    @DisplayName("악재 뉴스 FLAGGED — 탈락")
    void newsFlagged() {
        NewsRiskCheck flagged = NewsRiskCheck.of(12, List.of(
                new NewsRiskCheck.Flag("횡령", "제목", "https://n/1", Instant.now())));
        assertThat(policy.evaluate(check(score(82, InvestmentGrade.AA, true), flagged,
                price(PricePositionCheck.Status.OK)), SECTOR)).isEmpty();
    }

    @Test
    @DisplayName("뉴스 UNAVAILABLE — 근거 없음, 보수적 탈락")
    void newsUnavailable() {
        assertThat(policy.evaluate(check(score(82, InvestmentGrade.AA, true),
                NewsRiskCheck.unavailable(), price(PricePositionCheck.Status.OK)), SECTOR)).isEmpty();
    }

    @Test
    @DisplayName("시세위치 CAUTION(추격/고점근접) — 포함하되 이유문에 '⚠️ 시세 주의' 명시")
    void priceCautionIncludedWithNote() {
        Optional<ScreenedPick> pick = policy.evaluate(check(score(82, InvestmentGrade.AA, true),
                NewsRiskCheck.of(12, List.of()), cautionPrice()), SECTOR);

        assertThat(pick).isPresent();
        assertThat(pick.get().reason())
                .contains("⚠️ 시세 주의")
                .contains("추격 구간(4일 연속 상승)")
                .contains("52주 고점 근접");
    }

    @Test
    @DisplayName("시세 UNAVAILABLE — 근거 없음(종가 없어 매매계획 불가), 탈락")
    void priceUnavailable() {
        assertThat(policy.evaluate(check(score(82, InvestmentGrade.AA, true),
                NewsRiskCheck.of(12, List.of()), PricePositionCheck.unavailable()), SECTOR)).isEmpty();
    }

    @Test
    @DisplayName("시세 NO_DATA — 종가 없음, 탈락")
    void priceNoData() {
        assertThat(policy.evaluate(check(score(82, InvestmentGrade.AA, true),
                NewsRiskCheck.of(12, List.of()), PricePositionCheck.noData()), SECTOR)).isEmpty();
    }

    @Test
    @DisplayName("매매계획 없음(null) — 탈락")
    void noTradePlan() {
        BeginnerInvestmentCheck c = new BeginnerInvestmentCheck("267260",
                score(82, InvestmentGrade.AA, true), NewsRiskCheck.of(12, List.of()),
                price(PricePositionCheck.Status.OK), MacroCheck.of(List.of()), null);
        assertThat(policy.evaluate(c, SECTOR)).isEmpty();
    }
}
