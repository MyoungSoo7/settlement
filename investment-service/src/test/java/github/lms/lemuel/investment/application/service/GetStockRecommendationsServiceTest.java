package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.out.LoadStockRecommendationPort;
import github.lms.lemuel.investment.domain.StockRecommendation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetStockRecommendationsServiceTest {

    private final LoadStockRecommendationPort loadPort = mock(LoadStockRecommendationPort.class);
    private final GetStockRecommendationsService service = new GetStockRecommendationsService(loadPort);

    @Test
    @DisplayName("최신 추천 세트를 포트에서 그대로 반환한다")
    void returnsLatestSet() {
        List<StockRecommendation> set = List.of(StockRecommendation.rehydrate(
                "267260", "HD현대일렉트릭", "전력기기", "규칙 5종 통과",
                LocalDate.of(2026, 7, 15),
                new BigDecimal("797000"), new BigDecimal("704000"), new BigDecimal("908000"), 1));
        when(loadPort.loadLatest()).thenReturn(set);

        assertThat(service.getLatestRecommendations()).isEqualTo(set);
    }

    @Test
    @DisplayName("추천 세트가 없으면 빈 리스트")
    void emptyWhenNone() {
        when(loadPort.loadLatest()).thenReturn(List.of());

        assertThat(service.getLatestRecommendations()).isEmpty();
    }
}
