package github.lms.lemuel.economics.adapter.out.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.economics.application.port.out.EcosClientPort;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 한국은행 ECOS StatisticSearch HTTP 클라이언트.
 *
 * <p>URL: {@code {baseUrl}/StatisticSearch/{apiKey}/json/kr/1/10000/{statCode}/{cycle}/{start}/{end}/{itemCode}}
 *
 * <ul>
 *   <li>cycle D(일별) → 날짜 포맷 {@code yyyyMMdd}, M(월별) → {@code yyyyMM} (응답 TIME 도 동일 포맷).
 *       월별은 파싱 후 해당 월 1일({@link LocalDate} day=1)로 정규화한다.</li>
 *   <li>정상 응답: {@code {"StatisticSearch":{"list_total_count":n,"row":[{"TIME":"...","DATA_VALUE":"..."}]}}}</li>
 *   <li>오류 응답: HTTP 200 에 {@code {"RESULT":{"CODE":"INFO-200","MESSAGE":"..."}}} —
 *       INFO-200(데이터 없음)은 빈 리스트로 처리하고, 그 외 CODE 는 예외.</li>
 *   <li>{@code DATA_VALUE} 가 빈 문자열/{@code "-"} 인 row 는 skip(결측/휴장일).</li>
 * </ul>
 *
 * <p>페이지네이션은 YAGNI: 요청 상한 10000건 &gt; 일별 1년치(≈250건)라 단일 콜로 충분하다.
 */
@Component
public class EcosApiClient implements EcosClientPort {

    private static final Logger log = LoggerFactory.getLogger(EcosApiClient.class);
    private static final String NO_DATA_CODE = "INFO-200";
    private static final DateTimeFormatter DAILY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTHLY = DateTimeFormatter.ofPattern("yyyyMM");

    private final EcosProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public EcosApiClient(EcosProperties properties, RestClient.Builder restClientBuilder,
                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public List<Observation> fetchObservations(Indicator indicator, LocalDate from, LocalDate to) {
        IndicatorCycle cycle = indicator.cycle();
        DateTimeFormatter formatter = cycle == IndicatorCycle.M ? MONTHLY : DAILY;
        String start = formatter.format(from);
        String end = formatter.format(to);

        JsonNode root = getJson(indicator.ecosStatCode(), cycle.name(), start, end, indicator.ecosItemCode());

        JsonNode result = root.path("RESULT");
        if (!result.isMissingNode()) {
            String code = result.path("CODE").asText();
            if (NO_DATA_CODE.equals(code)) {
                return List.of();   // 조회 데이터 없음 — 에러 아님
            }
            throw new IllegalStateException("ECOS 오류 CODE=%s MESSAGE=%s (statCode=%s)"
                    .formatted(code, result.path("MESSAGE").asText(), indicator.ecosStatCode()));
        }

        JsonNode rows = root.path("StatisticSearch").path("row");
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }
        List<Observation> observations = new ArrayList<>();
        for (JsonNode row : rows) {
            BigDecimal value = parseValue(row.path("DATA_VALUE").asText(""));
            LocalDate observedDate = parseTime(row.path("TIME").asText(""), cycle);
            if (value == null || observedDate == null) {
                continue;   // 빈 값/"-"/파싱 불가 TIME — 결측/휴장일, 그 행만 skip
            }
            observations.add(new Observation(observedDate, value));
        }
        return observations;
    }

    // ---- 내부 구현 ----

    private JsonNode getJson(String statCode, String cycle, String start, String end, String itemCode) {
        String body = restClient.get()
                .uri("/StatisticSearch/{apiKey}/json/kr/1/10000/{statCode}/{cycle}/{start}/{end}/{itemCode}",
                        properties.apiKey(), statCode, cycle, start, end, itemCode)
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ECOS 응답 JSON 파싱 실패 (statCode=" + statCode + ")", e);
        }
    }

    /**
     * 응답 TIME 파싱 — 월별(M)은 해당 월 1일로 정규화.
     * parseValue 와 대칭으로, 파싱 불가한 TIME 은 그 행만 skip 하도록 null 을 돌려준다
     * (한 행의 이상값으로 지표 전체 수집을 죽이지 않는다).
     */
    private static LocalDate parseTime(String time, IndicatorCycle cycle) {
        try {
            if (cycle == IndicatorCycle.M) {
                return YearMonth.parse(time, MONTHLY).atDay(1);
            }
            return LocalDate.parse(time, DAILY);
        } catch (DateTimeParseException e) {
            log.warn("ECOS TIME 파싱 실패, skip: '{}'", time);
            return null;
        }
    }

    private static BigDecimal parseValue(String raw) {
        String cleaned = raw.replace(",", "").strip();   // 천단위 콤마 제거 후 파싱
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.warn("ECOS DATA_VALUE 파싱 실패, skip: '{}'", raw);
            return null;
        }
    }
}
