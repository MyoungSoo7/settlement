package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.out.LoadFinancialStatementsPort;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.InvestmentScorePolicy;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 종목 투자점수 조회: financial 공개 API 로 회계자료를 가져와 {@link InvestmentScorePolicy} 로 산정한다.
 * 회사 미존재/재무제표 없음이면 {@link InvestmentNotFoundException}(→404).
 *
 * <p>financial 조회는 비용이 있어 Caffeine 캐시(10분, {@code spring.cache} 설정)를 적용한다.
 */
@Service
public class GetInvestmentScoreService implements GetInvestmentScoreUseCase {

    private final LoadFinancialStatementsPort loadFinancialStatementsPort;
    private final InvestmentScorePolicy scorePolicy = new InvestmentScorePolicy();

    public GetInvestmentScoreService(LoadFinancialStatementsPort loadFinancialStatementsPort) {
        this.loadFinancialStatementsPort = loadFinancialStatementsPort;
    }

    @Override
    @Cacheable(cacheNames = "investmentScores", key = "#stockCode")
    public InvestmentScore getScore(String stockCode) {
        return loadFinancialStatementsPort.load(stockCode)
                .filter(github.lms.lemuel.investment.domain.CompanyFinancials::hasStatements)
                .map(scorePolicy::score)
                .orElseThrow(() -> new InvestmentNotFoundException(
                        "재무제표를 찾을 수 없습니다. stockCode=" + stockCode));
    }
}
