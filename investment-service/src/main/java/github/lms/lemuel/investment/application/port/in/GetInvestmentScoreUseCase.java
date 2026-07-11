package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.InvestmentScore;

/** 종목(stockCode)의 회계자료 기반 투자점수 조회 인바운드 포트. */
public interface GetInvestmentScoreUseCase {

    InvestmentScore getScore(String stockCode);
}
