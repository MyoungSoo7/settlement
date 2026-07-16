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
import java.util.function.Supplier;

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

        // 위성 3축은 원천 장애 시 해당 축만 UNAVAILABLE 로 강등한다(부분 실패 ≠ 전체 실패).
        // 축이 늘어도 degrade() 한 줄이면 되고, 강등·로깅은 이 단일 초크포인트에 모인다.
        NewsRiskCheck newsRisk = degrade("뉴스", stockCode,
                () -> loadCompanyNewsPort.loadRecentArticles(stockCode)
                        .map(newsRiskPolicy::scan)
                        .orElseGet(NewsRiskCheck::noData),
                NewsRiskCheck::unavailable);

        PriceAxis price = degrade("시세", stockCode,
                () -> {
                    PricePositionCheck pos = pricePositionPolicy.evaluate(loadDailyClosesPort.loadRecentYear(stockCode));
                    TradePlan plan = pos.hasQuote() ? tradePlanPolicy.plan(pos.latestClose(), budget) : null;
                    return new PriceAxis(pos, plan);
                },
                () -> new PriceAxis(PricePositionCheck.unavailable(), null));

        MacroCheck macro = degrade("거시", stockCode,
                () -> MacroCheck.of(loadEconomicIndicatorsPort.loadLatest()),
                MacroCheck::unavailable);

        return new BeginnerInvestmentCheck(stockCode, score, newsRisk, price.position(), macro, price.plan());
    }

    /**
     * 위성 축 원천 장애를 해당 축만 우아하게 강등(UNAVAILABLE)하는 단일 초크포인트.
     * 정상 계산은 {@code compute}, 장애 시 {@code onFailure} 의 축별 UNAVAILABLE 값으로 대체하고 경고만 남긴다.
     */
    private <T> T degrade(String axis, String stockCode, Supplier<T> compute, Supplier<T> onFailure) {
        try {
            return compute.get();
        } catch (Exception e) {
            log.warn("초보 투자 체크 — {} 축 강등(UNAVAILABLE): stockCode={}", axis, stockCode, e);
            return onFailure.get();
        }
    }

    /** 시세 축은 시세위치 + (시세가 있으면) 매매계획 두 값을 한 계산 단위로 묶어 함께 강등한다. */
    private record PriceAxis(PricePositionCheck position, TradePlan plan) {
    }
}
