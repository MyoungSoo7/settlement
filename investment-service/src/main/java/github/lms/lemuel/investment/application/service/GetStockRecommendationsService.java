package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.GetStockRecommendationsUseCase;
import github.lms.lemuel.investment.application.port.out.LoadStockRecommendationPort;
import github.lms.lemuel.investment.domain.StockRecommendation;
import org.springframework.stereotype.Service;

import java.util.List;

/** 최신 추천일 기준 종목 추천 세트 조회 — 규칙 스크리닝 산출물의 읽기 전용 서빙. */
@Service
public class GetStockRecommendationsService implements GetStockRecommendationsUseCase {

    private final LoadStockRecommendationPort loadStockRecommendationPort;

    public GetStockRecommendationsService(LoadStockRecommendationPort loadStockRecommendationPort) {
        this.loadStockRecommendationPort = loadStockRecommendationPort;
    }

    @Override
    public List<StockRecommendation> getLatestRecommendations() {
        return loadStockRecommendationPort.loadLatest();
    }
}
