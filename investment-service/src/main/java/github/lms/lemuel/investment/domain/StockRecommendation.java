package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 종목 추천 1건 — 예측이 아니라 <b>규칙 스크리닝 산출물</b>이다.
 *
 * <p>이유({@code reason})에는 통과한 규칙·수치·출처가 담기고, 가격 3종은 {@link TradePlan} 규칙
 * (1차매수 = 현재가 밴드, 손절 = 평균 매수가 −7%, 익절 = 평균 매수가 +20%)에서 산정된 값이다.
 * 가격 순서 불변식(손절 &lt; 1차매수 &lt; 익절)은 DB CHECK 와 이 도메인이 이중으로 강제한다.
 */
public record StockRecommendation(String stockCode, String stockName, String sector, String reason,
                                  LocalDate recommendedDate, BigDecimal entryPrice,
                                  BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                                  int displayOrder) {

    public StockRecommendation {
        requireText(stockName, "stockName");
        requireText(sector, "sector");
        requireText(reason, "reason");
        if (stockCode == null || !stockCode.matches("\\d{6}")) {
            throw new InvestmentInvariantViolationException(
                    "종목코드는 6자리 숫자여야 합니다.", stockCode);
        }
        if (recommendedDate == null) {
            throw new InvestmentInvariantViolationException("추천일(recommendedDate)은 필수입니다.");
        }
        requirePositive(entryPrice, "entryPrice");
        requirePositive(stopLossPrice, "stopLossPrice");
        requirePositive(takeProfitPrice, "takeProfitPrice");
        if (stopLossPrice.compareTo(entryPrice) >= 0 || entryPrice.compareTo(takeProfitPrice) >= 0) {
            throw new InvestmentInvariantViolationException(
                    "가격 규칙 위반 — 손절가 < 1차 매수가 < 익절가 순서여야 합니다. "
                            + "stopLoss=" + stopLossPrice + ", entry=" + entryPrice
                            + ", takeProfit=" + takeProfitPrice);
        }
    }

    /** 영속 저장소의 행을 도메인으로 복원한다 — 생성 경로는 이 팩토리 하나뿐. */
    public static StockRecommendation rehydrate(String stockCode, String stockName, String sector,
                                                String reason, LocalDate recommendedDate,
                                                BigDecimal entryPrice, BigDecimal stopLossPrice,
                                                BigDecimal takeProfitPrice, int displayOrder) {
        return new StockRecommendation(stockCode, stockName, sector, reason, recommendedDate,
                entryPrice, stopLossPrice, takeProfitPrice, displayOrder);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvestmentInvariantViolationException(field + " 은(는) 비어 있을 수 없습니다.");
        }
    }

    private static void requirePositive(BigDecimal price, String field) {
        if (price == null || price.signum() <= 0) {
            throw new InvestmentInvariantViolationException(field + " 은(는) 양수여야 합니다.", price);
        }
    }
}
