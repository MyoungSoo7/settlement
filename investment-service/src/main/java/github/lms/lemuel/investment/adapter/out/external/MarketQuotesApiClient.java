package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.application.port.out.LoadDailyClosesPort;
import github.lms.lemuel.investment.domain.DailyClose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * market-service 공개 API HTTP 클라이언트 — 일별 종가 시계열(52주 창 + 여유분)을 조회한다.
 *
 * <p>{@code GET {base}/api/market/stocks/{stockCode}/series?from={오늘-370일}} → points[].
 * 종목 미등록(404)이면 빈 리스트. 시세는 일별 종가라 실시간이 아니다(전일 기준).
 * 조회 결과는 10분 캐시된다.
 */
@Component
public class MarketQuotesApiClient implements LoadDailyClosesPort {

    private static final Logger log = LoggerFactory.getLogger(MarketQuotesApiClient.class);
    /** 52주(365일) 창을 항상 덮도록 여유분 포함. */
    private static final int WINDOW_DAYS = 370;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public MarketQuotesApiClient(RestClient.Builder restClientBuilder,
                                 ObjectMapper objectMapper,
                                 @Value("${app.market.base-url:http://localhost:8094}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    @Cacheable(cacheNames = "beginnerDailyCloses", key = "#stockCode")
    public List<DailyClose> loadRecentYear(String stockCode) {
        LocalDate from = LocalDate.now().minusDays(WINDOW_DAYS);
        JsonNode series = getJson("/api/market/stocks/" + stockCode + "/series?from=" + from);
        if (series == null) {
            return List.of(); // 종목 미등록
        }
        List<DailyClose> closes = new ArrayList<>();
        for (JsonNode point : series.path("points")) {
            BigDecimal close = decimal(point, "closePrice");
            LocalDate date = parseDate(point.get("baseDate"));
            if (close == null || date == null) {
                continue; // 결측 포인트는 판정에서 제외
            }
            closes.add(new DailyClose(date, close));
        }
        return closes;
    }

    /** GET → JsonNode. 404 는 null(미존재), 그 외 오류는 예외. */
    private JsonNode getJson(String path) {
        try {
            String body = restClient.get().uri(path).retrieve().body(String.class);
            return body == null ? null : objectMapper.readTree(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw new IllegalStateException("market API 오류 path=" + path + " status=" + e.getStatusCode(), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("market API 응답 파싱 실패 path=" + path, e);
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            log.warn("market 숫자 파싱 실패, 포인트 제외: field={}, raw={}", field, v.asText());
            return null;
        }
    }

    private static LocalDate parseDate(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            log.warn("market baseDate 파싱 실패, 포인트 제외: raw={}", node.asText());
            return null;
        }
    }
}
