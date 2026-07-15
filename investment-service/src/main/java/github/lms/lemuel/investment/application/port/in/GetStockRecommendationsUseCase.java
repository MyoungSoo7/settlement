package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.StockRecommendation;

import java.util.List;

/** 최신 추천일 기준 종목 추천 세트 조회 인바운드 포트. 추천 세트가 없으면 빈 리스트. */
public interface GetStockRecommendationsUseCase {

    List<StockRecommendation> getLatestRecommendations();
}
