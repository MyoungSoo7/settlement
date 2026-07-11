package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.application.port.out.LoadEconomicIndicatorsPort;
import github.lms.lemuel.investment.domain.EconomicIndicatorSnapshot;
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
 * economics-service 공개 API HTTP 클라이언트 — 경제지표 카탈로그의 최신값 스냅샷을 조회한다.
 *
 * <p>{@code GET {base}/api/economics/indicators} → [{code,name,unit,latest{observedDate,value},change{amount}}].
 * 최신값이 아직 없는 지표는 제외한다. 거시 지표는 종목 무관이라 단일 키로 10분 캐시된다.
 */
@Component
public class EconomicsIndicatorsApiClient implements LoadEconomicIndicatorsPort {

    private static final Logger log = LoggerFactory.getLogger(EconomicsIndicatorsApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public EconomicsIndicatorsApiClient(RestClient.Builder restClientBuilder,
                                        ObjectMapper objectMapper,
                                        @Value("${app.economics.base-url:http://localhost:8087}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    @Cacheable(cacheNames = "beginnerMacroIndicators", key = "'latest'")
    public List<EconomicIndicatorSnapshot> loadLatest() {
        JsonNode arr = getJson("/api/economics/indicators");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<EconomicIndicatorSnapshot> snapshots = new ArrayList<>();
        for (JsonNode node : arr) {
            JsonNode latest = node.path("latest");
            BigDecimal value = decimal(latest, "value");
            LocalDate observedDate = parseDate(latest.get("observedDate"));
            if (value == null || observedDate == null) {
                continue; // 아직 값이 적재되지 않은 지표
            }
            snapshots.add(new EconomicIndicatorSnapshot(
                    node.path("code").asText(null),
                    node.path("name").asText(null),
                    node.path("unit").asText(null),
                    value,
                    observedDate,
                    decimal(node.path("change"), "amount")));
        }
        return snapshots;
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
            throw new IllegalStateException("economics API 오류 path=" + path + " status=" + e.getStatusCode(), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("economics API 응답 파싱 실패 path=" + path, e);
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
            log.warn("economics 숫자 파싱 실패, null 처리: field={}, raw={}", field, v.asText());
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
            log.warn("economics observedDate 파싱 실패, 지표 제외: raw={}", node.asText());
            return null;
        }
    }
}
