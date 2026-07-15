package github.lms.lemuel.investment.adapter.in.web.dto;

import github.lms.lemuel.investment.domain.StockRecommendation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 종목 추천 세트 응답 — 추천일·고지문을 응답 자체에 필수 포함한다(소비 화면이 어디든 누락 불가).
 * 추천 세트가 없으면 recommendedDate=null + 빈 items.
 */
public record StockRecommendationsResponse(
        LocalDate recommendedDate,
        List<Item> items,
        String priceRule,
        String disclaimer) {

    /** 가격 3종이 예측이 아니라 규칙임을 화면 문구로 강제한다. */
    public static final String PRICE_RULE =
            "가격은 예측이 아니라 규칙입니다 — 1차매수가(현재가 밴드), 손절가(평균 매수가 -7% 전량 매도), "
                    + "1차익절가(평균 매수가 +20% 절반 매도). KRX 호가단위 내림, 수수료·세금 미반영.";

    public record Item(String stockCode, String stockName, String sector, String reason,
                       BigDecimal entryPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {

        static Item from(StockRecommendation reco) {
            return new Item(reco.stockCode(), reco.stockName(), reco.sector(), reco.reason(),
                    reco.entryPrice(), reco.stopLossPrice(), reco.takeProfitPrice());
        }
    }

    public static StockRecommendationsResponse from(List<StockRecommendation> recommendations) {
        LocalDate recommendedDate = recommendations.isEmpty()
                ? null
                : recommendations.getFirst().recommendedDate();
        return new StockRecommendationsResponse(
                recommendedDate,
                recommendations.stream().map(Item::from).toList(),
                PRICE_RULE,
                BeginnerCheckResponse.DISCLAIMER);
    }
}
