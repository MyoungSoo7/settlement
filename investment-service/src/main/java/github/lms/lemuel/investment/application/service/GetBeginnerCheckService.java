package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.GetBeginnerCheckUseCase;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.out.LoadCompanyNewsPort;
import github.lms.lemuel.investment.application.port.out.LoadDailyClosesPort;
import github.lms.lemuel.investment.application.port.out.LoadEconomicIndicatorsPort;
import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.MacroCheck;
import github.lms.lemuel.investment.domain.NewsRiskCheck;
import github.lms.lemuel.investment.domain.NewsRiskPolicy;
import github.lms.lemuel.investment.domain.PricePositionCheck;
import github.lms.lemuel.investment.domain.PricePositionPolicy;
import github.lms.lemuel.investment.domain.TradePlan;
import github.lms.lemuel.investment.domain.TradePlanPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 초보 투자 체크 조회 — 점수(회계) 축을 앵커로 뉴스(R3)·시세(R4·R5)·거시 축을 소비측 조인한다.
 *
 * <p>점수 축은 기존 {@link GetInvestmentScoreUseCase} 를 재사용(캐시 포함)하며 회계자료가 없으면
 * 기존 규약대로 404 로 전파한다. 위성 3축은 원천 장애 시 <b>해당 축만 UNAVAILABLE 로 강등</b>하고
 * 나머지 축은 정상 제공한다(부분 실패 ≠ 전체 실패). 각 위성 원천은 어댑터에서 10분 캐시된다.
 */
@Service
public class GetBeginnerCheckService implements GetBeginnerCheckUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetBeginnerCheckService.class);

    private final GetInvestmentScoreUseCase getInvestmentScoreUseCase;
    private final LoadCompanyNewsPort loadCompanyNewsPort;
    private final LoadDailyClosesPort loadDailyClosesPort;
    private final LoadEconomicIndicatorsPort loadEconomicIndicatorsPort;

    private final NewsRiskPolicy newsRiskPolicy = new NewsRiskPolicy();
    private final PricePositionPolicy pricePositionPolicy = new PricePositionPolicy();
    private final TradePlanPolicy tradePlanPolicy = new TradePlanPolicy();

    public GetBeginnerCheckService(GetInvestmentScoreUseCase getInvestmentScoreUseCase,
                                   LoadCompanyNewsPort loadCompanyNewsPort,
                                   LoadDailyClosesPort loadDailyClosesPort,
                                   LoadEconomicIndicatorsPort loadEconomicIndicatorsPort) {
        this.getInvestmentScoreUseCase = getInvestmentScoreUseCase;
        this.loadCompanyNewsPort = loadCompanyNewsPort;
        this.loadDailyClosesPort = loadDailyClosesPort;
        this.loadEconomicIndicatorsPort = loadEconomicIndicatorsPort;
    }

    @Override
    public BeginnerInvestmentCheck getCheck(String stockCode, BigDecimal budget) {
        if (budget != null && budget.signum() <= 0) {
            throw new IllegalArgumentException("budget 은 0 보다 커야 합니다: " + budget);
        }
        // 점수 축이 앵커 — 회계자료 없으면 InvestmentNotFoundException(404) 전파.
        InvestmentScore score = getInvestmentScoreUseCase.getScore(stockCode);

        NewsRiskCheck newsRisk;
        try {
            newsRisk = loadCompanyNewsPort.loadRecentArticles(stockCode)
                    .map(newsRiskPolicy::scan)
                    .orElseGet(NewsRiskCheck::noData);
        } catch (Exception e) {
            log.warn("초보 투자 체크 — 뉴스 축 강등(UNAVAILABLE): stockCode={}", stockCode, e);
            newsRisk = NewsRiskCheck.unavailable();
        }

        PricePositionCheck pricePosition;
        TradePlan tradePlan = null;
        try {
            pricePosition = pricePositionPolicy.evaluate(loadDailyClosesPort.loadRecentYear(stockCode));
            if (pricePosition.hasQuote()) {
                tradePlan = tradePlanPolicy.plan(pricePosition.latestClose(), budget);
            }
        } catch (Exception e) {
            log.warn("초보 투자 체크 — 시세 축 강등(UNAVAILABLE): stockCode={}", stockCode, e);
            pricePosition = PricePositionCheck.unavailable();
        }

        MacroCheck macro;
        try {
            macro = MacroCheck.of(loadEconomicIndicatorsPort.loadLatest());
        } catch (Exception e) {
            log.warn("초보 투자 체크 — 거시 축 강등(UNAVAILABLE)", e);
            macro = MacroCheck.unavailable();
        }

        return new BeginnerInvestmentCheck(stockCode, score, newsRisk, pricePosition, macro, tradePlan);
    }
}
