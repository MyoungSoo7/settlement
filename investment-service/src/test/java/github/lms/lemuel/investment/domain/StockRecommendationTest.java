package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockRecommendationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    private static StockRecommendation reco(BigDecimal entry, BigDecimal stopLoss, BigDecimal takeProfit) {
        return StockRecommendation.rehydrate("267260", "HD현대일렉트릭", "전력기기",
                "규칙 5종 통과", DATE, entry, stopLoss, takeProfit, 1);
    }

    @Test
    @DisplayName("rehydrate — 유효 입력이면 필드가 그대로 복원된다")
    void rehydrateValid() {
        StockRecommendation reco = reco(
                new BigDecimal("797000"), new BigDecimal("704000"), new BigDecimal("908000"));

        assertThat(reco.stockCode()).isEqualTo("267260");
        assertThat(reco.stockName()).isEqualTo("HD현대일렉트릭");
        assertThat(reco.recommendedDate()).isEqualTo(DATE);
        assertThat(reco.entryPrice()).isEqualByComparingTo("797000");
        assertThat(reco.stopLossPrice()).isEqualByComparingTo("704000");
        assertThat(reco.takeProfitPrice()).isEqualByComparingTo("908000");
        assertThat(reco.displayOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("종목코드가 6자리 숫자가 아니면 불변식 위반")
    void invalidStockCode() {
        assertThatThrownBy(() -> StockRecommendation.rehydrate("26726", "이름", "업종", "이유",
                DATE, new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("120"), 1))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("종목코드");
    }

    @Test
    @DisplayName("가격 순서 위반(손절 ≥ 1차매수) — 불변식 위반")
    void stopLossNotBelowEntry() {
        assertThatThrownBy(() -> reco(
                new BigDecimal("797000"), new BigDecimal("797000"), new BigDecimal("908000")))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("손절가 < 1차 매수가 < 익절가");
    }

    @Test
    @DisplayName("가격 순서 위반(익절 ≤ 1차매수) — 불변식 위반")
    void takeProfitNotAboveEntry() {
        assertThatThrownBy(() -> reco(
                new BigDecimal("797000"), new BigDecimal("704000"), new BigDecimal("797000")))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("손절가 < 1차 매수가 < 익절가");
    }

    @Test
    @DisplayName("가격이 0 이하면 불변식 위반")
    void nonPositivePrice() {
        assertThatThrownBy(() -> reco(
                BigDecimal.ZERO, new BigDecimal("704000"), new BigDecimal("908000")))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("entryPrice");
    }

    @Test
    @DisplayName("추천 이유가 공백이면 불변식 위반")
    void blankReason() {
        assertThatThrownBy(() -> StockRecommendation.rehydrate("267260", "이름", "업종", " ",
                DATE, new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("120"), 1))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("추천일이 null 이면 불변식 위반")
    void nullRecommendedDate() {
        assertThatThrownBy(() -> StockRecommendation.rehydrate("267260", "이름", "업종", "이유",
                null, new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("120"), 1))
                .isInstanceOf(InvestmentInvariantViolationException.class)
                .hasMessageContaining("recommendedDate");
    }
}
