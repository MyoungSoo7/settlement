package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.domain.DailyClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MarketQuotesApiClient — market 공개 API 시계열 파싱을 MockRestServiceServer 로 검증
 * (points 매핑 · 결측 포인트 제외 · 404 빈 리스트 · 비404 예외).
 */
class MarketQuotesApiClientTest {

    private static final String BASE = "http://market.test";

    private MockRestServiceServer server;
    private MarketQuotesApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MarketQuotesApiClient(builder, new ObjectMapper(), BASE);
    }

    /** 클라이언트가 오늘 기준 370일 전 from 을 붙이므로 테스트도 같은 식으로 기대 URI 를 만든다. */
    private static String seriesUri() {
        return BASE + "/api/market/stocks/005930/series?from=" + LocalDate.now().minusDays(370);
    }

    @Test
    @DisplayName("시계열 points 를 일별 종가로 매핑하고 결측 포인트는 제외한다")
    void mapsPointsAndSkipsMissing() {
        server.expect(requestTo(seriesUri())).andRespond(withSuccess("""
                {"stockCode":"005930","name":"삼성전자","market":"KOSPI","points":[
                  {"baseDate":"2026-07-08","closePrice":"62000"},
                  {"baseDate":"2026-07-09","closePrice":null},
                  {"baseDate":"2026-07-10","closePrice":"63500"}
                ]}
                """, MediaType.APPLICATION_JSON));

        List<DailyClose> closes = client.loadRecentYear("005930");

        assertThat(closes).hasSize(2);
        assertThat(closes.get(0).date()).isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(closes.get(0).close()).isEqualByComparingTo("62000");
        assertThat(closes.get(1).close()).isEqualByComparingTo("63500");
        server.verify();
    }

    @Test
    @DisplayName("종목 404 이면 빈 리스트 — 시세 축 NO_DATA 로 이어진다")
    void emptyWhenStockNotFound() {
        server.expect(requestTo(seriesUri())).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.loadRecentYear("005930")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("비404 오류는 IllegalStateException — 서비스가 축 강등으로 처리한다")
    void throwsOnServerError() {
        server.expect(requestTo(seriesUri())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatIllegalStateException().isThrownBy(() -> client.loadRecentYear("005930"))
                .withMessageContaining("market API 오류");
    }
}
