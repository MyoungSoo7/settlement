package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.StockRecommendation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 종목 추천 조회 아웃바운드 포트. */
public interface LoadStockRecommendationPort {

    /** 최신 추천일의 추천 세트를 display_order 순으로. 데이터가 없으면 빈 리스트. */
    List<StockRecommendation> loadLatest();

    /**
     * 저장된 최신 추천일만 조회한다(세트 본문은 읽지 않음).
     *
     * <p>크론이 "이미 이 시세 기준일로 돌았는가"를 판정하는 데 쓴다 — 세트 전체를 읽을 이유가 없다.
     *
     * @return 저장된 세트가 하나도 없으면 빈 값
     */
    Optional<LocalDate> loadLatestDate();
}
