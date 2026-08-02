package github.lms.lemuel.commondata.adapter.out.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort;
import github.lms.lemuel.commondata.domain.DataProvider;
import github.lms.lemuel.commondata.domain.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서울 열린데이터광장(openapi.seoul.go.kr) 봉투 HTTP 클라이언트.
 *
 * <p>URL: {@code {endpoint}/{KEY}/json/{service}/{START}/{END}[/{path}..]} — 인증키가 쿼리가 아닌
 * <b>경로</b>에 들어가고, 페이지네이션도 START/END 인덱스(1-base, 양끝 포함) 경로 세그먼트다.
 *
 * <ul>
 *   <li>{@code service}(필수)·{@code path}(선택 후행 경로, {@code /} 로 다중 세그먼트)는 소스
 *       defaultParams 예약 키로 선언하고, sync override 로 호출별 교체할 수 있다(장소별 수집 등).</li>
 *   <li>표준 봉투: {@code {서비스명:{list_total_count, RESULT:{CODE:"INFO-000"}, row:[..]}}}.</li>
 *   <li>citydata 변형 지원: 서비스명과 다른 배열 키(예: {@code SeoulRtd.citydata_ppltn})와
 *       플랫 {@code "RESULT.CODE"} 를 함께 인식한다 — 실시간 도시데이터 계열 봉투.</li>
 *   <li>INFO-200(데이터 없음)은 빈 리스트, 그 외 비 INFO-000 코드는 예외.</li>
 *   <li>list_total_count 기준 전 구간 순회, 미제공이면 "받은 행 &lt; 윈도우 크기" 로 종료.
 *       {@code MAX_PAGES} 안전 상한으로 무한 루프를 막는다.</li>
 * </ul>
 */
@Component
public class SeoulOpenApiClient implements DataPortalClientPort {

    private static final Logger log = LoggerFactory.getLogger(SeoulOpenApiClient.class);
    private static final String OK_CODE = "INFO-000";
    private static final String NO_DATA_CODE = "INFO-200";
    /** defaultParams 예약 키 — 열린데이터광장 서비스명(URL 경로 세그먼트). */
    static final String PARAM_SERVICE = "service";
    /** defaultParams 예약 키 — START/END 뒤에 붙는 후행 경로(장소명·날짜 등, {@code /} 구분). */
    static final String PARAM_PATH = "path";
    /** START/END 를 무시하고 같은 구간을 반복하는 API 로부터의 무한 루프 방지 상한. */
    private static final int MAX_PAGES = 100;

    private final SeoulOpenApiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SeoulOpenApiClient(SeoulOpenApiProperties properties, RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DataProvider provider() {
        return DataProvider.SEOUL_OPENAPI;
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public List<PortalItem> fetchItems(DataSource source, Map<String, String> overrideParams) {
        Map<String, String> params = new LinkedHashMap<>(source.defaultParams());
        if (overrideParams != null) {
            params.putAll(overrideParams);
        }
        String service = params.get(PARAM_SERVICE);
        if (service == null || service.isBlank()) {
            throw new IllegalStateException("SEOUL_OPENAPI 소스는 defaultParams 에 예약 키 '"
                    + PARAM_SERVICE + "'(열린데이터광장 서비스명) 등록이 필수입니다 (source="
                    + source.code() + ")");
        }
        String path = params.get(PARAM_PATH);

        List<PortalItem> all = new ArrayList<>();
        int windowSize = source.pageSize();
        int start = 1;
        for (int page = 0; page < MAX_PAGES; page++) {
            int end = start + windowSize - 1;
            Page result = getPage(source, service, path, start, end);
            if (result == null) {
                break;   // INFO-200 데이터 없음
            }
            if (result.rows.isEmpty()) {
                break;
            }
            for (JsonNode row : result.rows) {
                PortalItem item = toPortalItem(row, source.keyFields());
                if (item != null) {
                    all.add(item);
                }
            }
            boolean lastPage = result.totalCount > 0
                    ? end >= result.totalCount
                    : result.rows.size() < windowSize;
            if (lastPage) {
                break;
            }
            start = end + 1;
        }
        return all;
    }

    // ---- 내부 구현 ----

    private record Page(List<JsonNode> rows, int totalCount) { }

    /** 한 구간(START~END) 파싱 결과. INFO-200(데이터 없음)이면 null, 그 외 오류 코드는 예외. */
    private Page getPage(DataSource source, String service, String path, int start, int end) {
        URI uri = uriFor(source.endpoint(), service, path, start, end);
        String rawBody = restClient.get().uri(uri).retrieve().body(String.class);
        JsonNode root = readTree(rawBody, source.code());

        JsonNode serviceNode = root.path(service);
        String code = resultCode(root, serviceNode);
        if (NO_DATA_CODE.equals(code)) {
            return null;
        }
        if (!OK_CODE.equals(code)) {
            throw new IllegalStateException("서울 열린데이터광장 API 오류 CODE=%s MESSAGE=%s (source=%s)"
                    .formatted(code, resultMessage(root, serviceNode), source.code()));
        }
        return new Page(extractRows(root, serviceNode),
                serviceNode.path("list_total_count").asInt(0));
    }

    /** 경로형 URL 조립 — 한글 장소명 등 비 ASCII 세그먼트는 percent-encode 된다. */
    private URI uriFor(String endpoint, String service, String path, int start, int end) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint)
                .pathSegment(properties.apiKey(), "json", service,
                        String.valueOf(start), String.valueOf(end));
        if (path != null && !path.isBlank()) {
            for (String segment : path.split("/")) {
                if (!segment.isBlank()) {
                    builder.pathSegment(segment);
                }
            }
        }
        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    private JsonNode readTree(String rawBody, String sourceCode) {
        String body = rawBody == null ? "" : rawBody.strip();
        // 형식 오류/일부 오류 응답은 XML 로 온다 — 원인을 남긴다.
        if (body.startsWith("<")) {
            throw new IllegalStateException("서울 열린데이터광장이 XML 을 반환했습니다 (source=" + sourceCode
                    + ") — 인증키 유효성과 요청 타입(json) 경로를 확인하세요");
        }
        try {
            return objectMapper.readTree(body.isEmpty() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("서울 열린데이터광장 응답 JSON 파싱 실패 (source=" + sourceCode
                    + ") — 인증키/쿼터를 확인하세요", e);
        }
    }

    /**
     * RESULT 코드 해석 — 네 가지 봉투 변형 지원:
     * ① {@code {서비스명:{RESULT:{CODE}}}}(표준) ② {@code {RESULT:{CODE}}}(오류/데이터없음)
     * ③ {@code {RESULT:{"RESULT.CODE": ..}}}(citydata 실측 — 점 포함 키가 RESULT 객체 안)
     * ④ {@code {"RESULT.CODE": ..}}(플랫 루트 키).
     */
    private static String resultCode(JsonNode root, JsonNode serviceNode) {
        return firstNonEmpty(
                serviceNode.path("RESULT").path("CODE").asText(""),
                root.path("RESULT").path("CODE").asText(""),
                root.path("RESULT").path("RESULT.CODE").asText(""),
                root.path("RESULT.CODE").asText(""));
    }

    private static String resultMessage(JsonNode root, JsonNode serviceNode) {
        return firstNonEmpty(
                serviceNode.path("RESULT").path("MESSAGE").asText(""),
                root.path("RESULT").path("MESSAGE").asText(""),
                root.path("RESULT").path("RESULT.MESSAGE").asText(""),
                root.path("RESULT.MESSAGE").asText(""));
    }

    private static String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * 아이템 배열 추출 — 표준은 {@code {서비스명}.row[]}, citydata 계열은 서비스명과 다른
     * 루트 배열 키(예: {@code SeoulRtd.citydata_ppltn})라 루트의 첫 배열 필드로 폴백한다.
     */
    private static List<JsonNode> extractRows(JsonNode root, JsonNode serviceNode) {
        JsonNode row = serviceNode.path("row");
        if (row.isArray()) {
            return toList(row);
        }
        if (serviceNode.isArray()) {
            return toList(serviceNode);
        }
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (entry.getValue().isArray()) {
                return toList(entry.getValue());
            }
        }
        return List.of();
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    /** 1 아이템 → PortalItem. 객체가 아니면 skip(null). payload 는 JSON 원문 그대로 보존. */
    private PortalItem toPortalItem(JsonNode row, List<String> keyFields) {
        if (!row.isObject()) {
            log.warn("객체가 아닌 아이템 skip: {}", row.getNodeType());
            return null;
        }
        String payload = row.toString();
        return new PortalItem(RecordKeys.resolve(row, keyFields, payload), payload);
    }
}
