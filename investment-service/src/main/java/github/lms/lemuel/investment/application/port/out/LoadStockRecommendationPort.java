package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.StockRecommendation;

import java.util.List;

/** 종목 추천 조회 아웃바운드 포트. */
public interface LoadStockRecommendationPort {

    /** 최신 추천일의 추천 세트를 display_order 순으로. 데이터가 없으면 빈 리스트. */
    List<StockRecommendation> loadLatest();
}
