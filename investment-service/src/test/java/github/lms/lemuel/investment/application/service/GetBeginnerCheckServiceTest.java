package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.out.LoadCompanyNewsPort;
import github.lms.lemuel.investment.application.port.out.LoadDailyClosesPort;
import github.lms.lemuel.investment.application.port.out.LoadEconomicIndicatorsPort;
import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;
import github.lms.lemuel.investment.domain.DailyClose;
import github.lms.lemuel.investment.domain.EconomicIndicatorSnapshot;
import github.lms.lemuel.investment.domain.InvestmentGrade;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.MacroCheck;
import github.lms.lemuel.investment.domain.NewsArticleSummary;
import github.lms.lemuel.investment.domain.NewsRiskCheck;
import github.lms.lemuel.investment.domain.PricePositionCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GetBeginnerCheckService — 점수 앵커(404 전파) + 위성 3축(뉴스/시세/거시)의
 * 정상 조인·NO_DATA 구분·축별 우아한 강등(UNAVAILABLE)을 검증한다.
 */
class GetBeginnerCheckServiceTest {

    private static final String STOCK = "005930";
    private static final LocalDate LATEST = LocalDate.of(2026, 7, 10);

    private final GetInvestmentScoreUseCase getScore = mock(GetInvestmentScoreUseCase.class);
    private final LoadCompanyNewsPort newsPort = mock(LoadCompanyNewsPort.class);
    private final LoadDailyClosesPort closesPort = mock(LoadDailyClosesPort.class);
    private final LoadEconomicIndicatorsPort econPort = mock(LoadEconomicIndicatorsPort.class);

    private GetBeginnerCheckService service;

    @BeforeEach
    void setUp() {
        service = new GetBeginnerCheckService(getScore, newsPort, closesPort, econPort);
        when(getScore.getScore(STOCK)).thenReturn(score());
        when(newsPort.loadRecentArticles(STOCK)).thenReturn(Optional.of(List.of(
                new NewsArticleSummary("신제품 출시", "요약", "https://n/1", Instant.now()))));
        when(closesPort.loadRecentYear(STOCK)).thenReturn(List.of(
                new DailyClose(LATEST.minusDays(2), new BigDecimal("62000")),
                new DailyClose(LATEST.minusDays(1), new BigDecimal("63000")),
                new DailyClose(LATEST, new BigDecimal("63500"))));
        when(econPort.loadLatest()).thenReturn(List.of(new EconomicIndicatorSnapshot(
                "BASE_RATE", "기준금리", "%", new BigDecimal("2.50"),
                LATEST, new BigDecimal("-0.25"))));
    }

    private static InvestmentScore score() {
        return new InvestmentScore(STOCK, "삼성전자", "KOSPI", 2025, 82, InvestmentGrade.AA, true,
                new InvestmentScore.Profitability(30, new BigDecimal("10.0"), new BigDecimal("5.0")),
                new InvestmentScore.Stability(30, new BigDecimal("66.6"), new BigDecimal("60.0")),
                new InvestmentScore.Growth(22, new BigDecimal("8.0"), new BigDecimal("12.0")));
    }

    @Test
    @DisplayName("4축 정상 — 점수·뉴스 CLEAR·시세 판정·거시 OK·예산 매매계획까지 조인된다")
    void joinsAllAxes() {
        BeginnerInvestmentCheck check = service.getCheck(STOCK, new BigDecimal("3000000"));

        assertThat(check.stockCode()).isEqualTo(STOCK);
        assertThat(check.score().totalScore()).isEqualTo(82);
        assertThat(check.newsRisk().status()).isEqualTo(NewsRiskCheck.Status.CLEAR);
        assertThat(check.newsRisk().scannedCount()).isEqualTo(1);
        assertThat(check.pricePosition().latestClose()).isEqualByComparingTo("63500");
        assertThat(check.macro().status()).isEqualTo(MacroCheck.Status.OK);
        assertThat(check.tradePlan()).isNotNull();
        assertThat(check.tradePlan().feasible()).isTrue();
        assertThat(check.tradePlan().totalQuantity()).isEqualTo(49);
    }

    @Test
    @DisplayName("예산 미지정이면 매매계획은 가격 레벨 전용(수량 null)")
    void planWithoutBudget() {
        BeginnerInvestmentCheck check = service.getCheck(STOCK, null);

        assertThat(check.tradePlan()).isNotNull();
        assertThat(check.tradePlan().feasible()).isTrue();
        assertThat(check.tradePlan().totalQuantity()).isNull();
    }

    @Test
    @DisplayName("company 미등록(Optional.empty)이면 뉴스 축은 NO_DATA — 악재 없음과 구분")
    void newsNoDataWhenCompanyUnknown() {
        when(newsPort.loadRecentArticles(STOCK)).thenReturn(Optional.empty());

        BeginnerInvestmentCheck check = service.getCheck(STOCK, null);

        assertThat(check.newsRisk().status()).isEqualTo(NewsRiskCheck.Status.NO_DATA);
    }

    @Test
    @DisplayName("뉴스 원천 장애 시 뉴스 축만 UNAVAILABLE — 나머지 축은 정상")
    void degradesOnlyNewsAxis() {
        when(newsPort.loadRecentArticles(anyString())).thenThrow(new IllegalStateException("company down"));

        BeginnerInvestmentCheck check = service.getCheck(STOCK, new BigDecimal("3000000"));

        assertThat(check.newsRisk().status()).isEqualTo(NewsRiskCheck.Status.UNAVAILABLE);
        assertThat(check.pricePosition().status()).isNotEqualTo(PricePositionCheck.Status.UNAVAILABLE);
        assertThat(check.macro().status()).isEqualTo(MacroCheck.Status.OK);
        assertThat(check.tradePlan()).isNotNull();
    }

    @Test
    @DisplayName("시세 없음(NO_DATA)이면 매매계획도 산정하지 않는다")
    void noTradePlanWithoutQuotes() {
        when(closesPort.loadRecentYear(STOCK)).thenReturn(List.of());

        BeginnerInvestmentCheck check = service.getCheck(STOCK, new BigDecimal("3000000"));

        assertThat(check.pricePosition().status()).isEqualTo(PricePositionCheck.Status.NO_DATA);
        assertThat(check.tradePlan()).isNull();
    }

    @Test
    @DisplayName("시세 원천 장애 시 시세 축 UNAVAILABLE + 매매계획 null")
    void degradesPriceAxis() {
        when(closesPort.loadRecentYear(anyString())).thenThrow(new IllegalStateException("market down"));

        BeginnerInvestmentCheck check = service.getCheck(STOCK, new BigDecimal("3000000"));

        assertThat(check.pricePosition().status()).isEqualTo(PricePositionCheck.Status.UNAVAILABLE);
        assertThat(check.tradePlan()).isNull();
        assertThat(check.newsRisk().status()).isEqualTo(NewsRiskCheck.Status.CLEAR);
    }

    @Test
    @DisplayName("거시 원천 장애 시 거시 축만 UNAVAILABLE")
    void degradesMacroAxis() {
        when(econPort.loadLatest()).thenThrow(new IllegalStateException("economics down"));

        BeginnerInvestmentCheck check = service.getCheck(STOCK, null);

        assertThat(check.macro().status()).isEqualTo(MacroCheck.Status.UNAVAILABLE);
        assertThat(check.newsRisk().status()).isEqualTo(NewsRiskCheck.Status.CLEAR);
    }

    @Test
    @DisplayName("악재 기사가 있으면 뉴스 축 FLAGGED + 키워드 플래그")
    void flagsRiskyNews() {
        when(newsPort.loadRecentArticles(STOCK)).thenReturn(Optional.of(List.of(
                new NewsArticleSummary("대규모 유상증자 결정", "요약", "https://n/2", Instant.now()))));

        BeginnerInvestmentCheck check = service.getCheck(STOCK, null);

        assertThat(check.newsRisk().status()).isEqualTo(NewsRiskCheck.Status.FLAGGED);
        assertThat(check.newsRisk().flags().get(0).keyword()).isEqualTo("유상증자");
    }

    @Test
    @DisplayName("budget 이 0 이하이면 IllegalArgumentException(400)")
    void rejectsNonPositiveBudget() {
        assertThatThrownBy(() -> service.getCheck(STOCK, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("회계자료가 없으면(점수 앵커) InvestmentNotFoundException 그대로 전파(404)")
    void propagatesScoreNotFound() {
        when(getScore.getScore(STOCK)).thenThrow(new InvestmentNotFoundException("없음"));

        assertThatThrownBy(() -> service.getCheck(STOCK, null))
                .isInstanceOf(InvestmentNotFoundException.class);
    }
}
