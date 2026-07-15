package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.GetBeginnerCheckUseCase;
import github.lms.lemuel.investment.application.port.out.SaveStockRecommendationPort;
import github.lms.lemuel.investment.config.ScreeningProperties;
import github.lms.lemuel.investment.config.ScreeningProperties.UniverseEntry;
import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;
import github.lms.lemuel.investment.domain.InvestmentGrade;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.MacroCheck;
import github.lms.lemuel.investment.domain.NewsRiskCheck;
import github.lms.lemuel.investment.domain.PricePositionCheck;
import github.lms.lemuel.investment.domain.StockRecommendation;
import github.lms.lemuel.investment.domain.TradePlan;
import github.lms.lemuel.investment.domain.TradePlanPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreenRecommendationsServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 16);
    private static final BigDecimal PRICE = new BigDecimal("50000");

    private final GetBeginnerCheckUseCase getCheck = mock(GetBeginnerCheckUseCase.class);
    private final SaveStockRecommendationPort savePort = mock(SaveStockRecommendationPort.class);

    private ScreenRecommendationsService service(ScreeningProperties props) {
        return new ScreenRecommendationsService(getCheck, savePort, props);
    }

    private static ScreeningProperties props(int maxPicks, boolean diversify, UniverseEntry... universe) {
        return new ScreeningProperties(List.of(universe), maxPicks, diversify, "0 0 18 * * MON-FRI", "Asia/Seoul");
    }

    private static BeginnerInvestmentCheck passing(String code, String name, int score) {
        InvestmentScore s = new InvestmentScore(code, name, "KOSPI", 2025, score, InvestmentGrade.AA, true,
                new InvestmentScore.Profitability(30, new BigDecimal("15.0"), new BigDecimal("6.0")),
                new InvestmentScore.Stability(30, new BigDecimal("50.0"), new BigDecimal("60.0")),
                new InvestmentScore.Growth(25, new BigDecimal("10.0"), new BigDecimal("12.0")));
        TradePlan plan = new TradePlanPolicy().plan(PRICE, null);
        PricePositionCheck pp = new PricePositionCheck(PricePositionCheck.Status.OK, AS_OF, PRICE,
                1, false, new BigDecimal("55000"), new BigDecimal("30000"), new BigDecimal("-9.0"), false);
        return new BeginnerInvestmentCheck(code, s, NewsRiskCheck.of(10, List.of()), pp,
                MacroCheck.of(List.of()), plan);
    }

    private static BeginnerInvestmentCheck notInvestable(String code) {
        InvestmentScore s = new InvestmentScore(code, "부적격", "KOSPI", 2025, 40, InvestmentGrade.B, false,
                new InvestmentScore.Profitability(10, new BigDecimal("2.0"), new BigDecimal("1.0")),
                new InvestmentScore.Stability(10, new BigDecimal("200.0"), new BigDecimal("20.0")),
                new InvestmentScore.Growth(10, new BigDecimal("-5.0"), new BigDecimal("-8.0")));
        TradePlan plan = new TradePlanPolicy().plan(PRICE, null);
        PricePositionCheck pp = new PricePositionCheck(PricePositionCheck.Status.OK, AS_OF, PRICE,
                1, false, new BigDecimal("55000"), new BigDecimal("30000"), new BigDecimal("-9.0"), false);
        return new BeginnerInvestmentCheck(code, s, NewsRiskCheck.of(10, List.of()), pp,
                MacroCheck.of(List.of()), plan);
    }

    @SuppressWarnings("unchecked")
    private List<StockRecommendation> captureSaved() {
        ArgumentCaptor<List<StockRecommendation>> captor = ArgumentCaptor.forClass(List.class);
        verify(savePort).replaceForDate(eq(AS_OF), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("통과 종목을 투자점수 내림차순으로 정렬하고 displayOrder 1..N 을 배정해 저장")
    void ranksByScoreDescending() {
        when(getCheck.getCheck("267260", null)).thenReturn(passing("267260", "HD현대일렉트릭", 70));
        when(getCheck.getCheck("005930", null)).thenReturn(passing("005930", "삼성전자", 85));
        when(getCheck.getCheck("033780", null)).thenReturn(passing("033780", "KT&G", 60));

        int saved = service(props(3, true,
                new UniverseEntry("267260", "전력기기"),
                new UniverseEntry("005930", "반도체"),
                new UniverseEntry("033780", "필수소비재"))).screen(AS_OF);

        assertThat(saved).isEqualTo(3);
        List<StockRecommendation> set = captureSaved();
        assertThat(set).extracting(StockRecommendation::stockCode)
                .containsExactly("005930", "267260", "033780");
        assertThat(set).extracting(StockRecommendation::displayOrder)
                .containsExactly(1, 2, 3);
        assertThat(set).allSatisfy(r -> assertThat(r.recommendedDate()).isEqualTo(AS_OF));
    }

    @Test
    @DisplayName("규칙 미통과 종목은 세트에서 제외")
    void excludesFailingStock() {
        when(getCheck.getCheck("267260", null)).thenReturn(passing("267260", "HD현대일렉트릭", 80));
        when(getCheck.getCheck("005930", null)).thenReturn(notInvestable("005930"));

        service(props(3, true,
                new UniverseEntry("267260", "전력기기"),
                new UniverseEntry("005930", "반도체"))).screen(AS_OF);

        assertThat(captureSaved()).extracting(StockRecommendation::stockCode).containsExactly("267260");
    }

    @Test
    @DisplayName("개별 종목 조회 예외는 그 종목만 건너뛰고 나머지는 진행")
    void skipsStockOnError() {
        when(getCheck.getCheck("267260", null)).thenReturn(passing("267260", "HD현대일렉트릭", 80));
        when(getCheck.getCheck("005930", null)).thenThrow(new RuntimeException("원천 장애"));

        service(props(3, true,
                new UniverseEntry("267260", "전력기기"),
                new UniverseEntry("005930", "반도체"))).screen(AS_OF);

        assertThat(captureSaved()).extracting(StockRecommendation::stockCode).containsExactly("267260");
    }

    @Test
    @DisplayName("업종 분산 ON — 같은 업종은 최고 점수 1개만 남김")
    void sectorDiversificationKeepsBestPerSector() {
        when(getCheck.getCheck("005930", null)).thenReturn(passing("005930", "삼성전자", 90));
        when(getCheck.getCheck("000660", null)).thenReturn(passing("000660", "SK하이닉스", 80));

        service(props(3, true,
                new UniverseEntry("005930", "반도체"),
                new UniverseEntry("000660", "반도체"))).screen(AS_OF);

        assertThat(captureSaved()).extracting(StockRecommendation::stockCode).containsExactly("005930");
    }

    @Test
    @DisplayName("업종 분산 OFF — 같은 업종도 모두 유지(점수순)")
    void noDiversificationKeepsAll() {
        when(getCheck.getCheck("005930", null)).thenReturn(passing("005930", "삼성전자", 90));
        when(getCheck.getCheck("000660", null)).thenReturn(passing("000660", "SK하이닉스", 80));

        service(props(3, false,
                new UniverseEntry("005930", "반도체"),
                new UniverseEntry("000660", "반도체"))).screen(AS_OF);

        assertThat(captureSaved()).extracting(StockRecommendation::stockCode)
                .containsExactly("005930", "000660");
    }

    @Test
    @DisplayName("maxPicks 상한 — 통과가 많아도 상위 N 만 저장")
    void capsAtMaxPicks() {
        when(getCheck.getCheck("005930", null)).thenReturn(passing("005930", "삼성전자", 90));
        when(getCheck.getCheck("267260", null)).thenReturn(passing("267260", "HD현대일렉트릭", 80));
        when(getCheck.getCheck("033780", null)).thenReturn(passing("033780", "KT&G", 70));

        int saved = service(props(2, true,
                new UniverseEntry("005930", "반도체"),
                new UniverseEntry("267260", "전력기기"),
                new UniverseEntry("033780", "필수소비재"))).screen(AS_OF);

        assertThat(saved).isEqualTo(2);
        assertThat(captureSaved()).extracting(StockRecommendation::stockCode)
                .containsExactly("005930", "267260");
    }

    @Test
    @DisplayName("통과 종목 0 — 빈 세트로 교체(멱등), 0 반환")
    void zeroPassSavesEmpty() {
        when(getCheck.getCheck("005930", null)).thenReturn(notInvestable("005930"));

        int saved = service(props(3, true, new UniverseEntry("005930", "반도체"))).screen(AS_OF);

        assertThat(saved).isZero();
        assertThat(captureSaved()).isEmpty();
    }
}
