package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.StockRecommendation;

import java.time.LocalDate;
import java.util.List;

/** 종목 추천 세트 저장 아웃바운드 포트. */
public interface SaveStockRecommendationPort {

    /**
     * 해당 추천일의 기존 세트를 지우고 새 세트로 교체한다.
     *
     * <p>같은 날 재스크리닝(수동 트리거·크론 재실행)이 마지막 결과로 수렴하도록 <b>삭제 후 삽입</b>으로
     * 멱등을 보장한다. {@code recommendations} 가 비면 그날치는 비워진다(최신 추천일이 이전 세트로 되돌아감).
     */
    void replaceForDate(LocalDate recommendedDate, List<StockRecommendation> recommendations);
}
