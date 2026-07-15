package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 규칙 스크리닝을 통과한 종목 후보 — 순위·추천일 배정 전의 중간 산출물.
 *
 * <p>{@code score}(투자점수)는 세트 내 정렬 기준으로만 쓰이며 저장되지 않는다.
 * 추천일과 표시순서가 정해지면 {@link #toRecommendation(LocalDate, int)} 로 {@link StockRecommendation} 이 된다.
 */
public record ScreenedPick(String stockCode, String stockName, String sector, String reason,
                           BigDecimal entryPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                           int score) {

    public StockRecommendation toRecommendation(LocalDate recommendedDate, int displayOrder) {
        return StockRecommendation.rehydrate(stockCode, stockName, sector, reason, recommendedDate,
                entryPrice, stopLossPrice, takeProfitPrice, displayOrder);
    }
}
