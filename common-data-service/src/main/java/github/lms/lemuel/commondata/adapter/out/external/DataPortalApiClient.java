package github.lms.lemuel.commondata.adapter.out.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort;
import github.lms.lemuel.commondata.domain.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털(data.go.kr) 표준 봉투 HTTP 클라이언트.
 *
 * <p>URL: {@code {endpoint}?serviceKey=..&numOfRows=..&pageNo=..&{defaultParams}&{override}}
 *
 * <ul>
 *   <li>정상 응답: {@code {"response":{"header":{"resultCode":"00"},"body":{"totalCount":n,"items":{"item":[..]}}}}}</li>
 *   <li>데이터 없음: resultCode {@code "03"}(NODATA) → 빈 리스트. 그 외 코드는 예외.</li>
 *   <li>아이템 노드는 {@code body.items.item}(배열/단일 객체) 또는 {@code body.items}(배열) 모두 지원.</li>
 *   <li>페이지네이션: totalCount 기준 전 페이지 순회. totalCount 를 안 주는 API 는
 *       "받은 행 < pageSize" 로 종료 판정하고, MAX_PAGES 안전 상한으로 무한 루프를 막는다.</li>
 *   <li>JSON 형식 지정 파라미터({@code _type}/{@code resultType})는 API 마다 달라 클라이언트가
 *       임의로 붙이지 않는다 — 소스 defaultParams 로 등록하는 것이 계약.</li>
 * </ul>
 */
@Component
public class DataPortalApiClient implements DataPortalClientPort {

    private static final Logger log = LoggerFactory.getLogger(DataPortalApiClient.class);
    private static final String OK_CODE = "00";
    private static final String NO_DATA_CODE = "03";
    /** pageNo 를 무시하고 같은 페이지를 반복하는 API 로부터의 무한 루프 방지 상한. */
    private static final int MAX_PAGES = 100;
    private static final String KEY_JOIN = "|";
    private static final int MAX_KEY_LENGTH = 300;

    private final DataPortalProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DataPortalApiClient(DataPortalProperties properties, RestClient.Builder restClientBuilder,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
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

        List<PortalItem> all = new ArrayList<>();
        int pageNo = 1;
        while (pageNo <= MAX_PAGES) {
            JsonNode body = getBody(source, params, pageNo);
            if (body == null) {
                break;   // NODATA
            }
            List<JsonNode> items = extractItems(body);
            if (items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                PortalItem portalItem = toPortalItem(item, source.keyFields());
                if (portalItem != null) {
                    all.add(portalItem);
                }
            }
            int totalCount = body.path("totalCount").asInt(0);
            long fetched = (long) pageNo * source.pageSize();
            boolean lastPage = totalCount > 0
                    ? fetched >= totalCount
                    : items.size() < source.pageSize();
            if (lastPage) {
                break;
            }
            pageNo++;
        }
        return all;
    }

    // ---- 내부 구현 ----

    /** 한 페이지 body 노드. NODATA(03)면 null, 그 외 오류 코드는 예외. */
    private JsonNode getBody(DataSource source, Map<String, String> params, int pageNo) {
        // serviceKey(Decoding 키)에 '+','/','=' 등이 들어갈 수 있어 queryParam 으로 한 번만 인코딩되게 한다.
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(source.endpoint())
                .queryParam("serviceKey", properties.apiKey())
                .queryParam("numOfRows", source.pageSize())
                .queryParam("pageNo", pageNo);
        params.forEach(builder::queryParam);
        URI uri = builder.build().toUri();

        String rawBody = restClient.get().uri(uri).retrieve().body(String.class);
        JsonNode root = readTree(rawBody, source.code());
        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText("");
        if (NO_DATA_CODE.equals(code)) {
            return null;
        }
        if (!OK_CODE.equals(code)) {
            throw new IllegalStateException("공공데이터포털 API 오류 resultCode=%s resultMsg=%s (source=%s)"
                    .formatted(code, header.path("resultMsg").asText(""), source.code()));
        }
        return root.path("response").path("body");
    }

    private JsonNode readTree(String rawBody, String sourceCode) {
        String body = rawBody == null ? "" : rawBody.strip();
        // 인증키 오류/JSON 형식 파라미터 누락 시 XML(OpenAPI_ServiceResponse)이 온다 — 원인을 남긴다.
        if (body.startsWith("<")) {
            throw new IllegalStateException("공공데이터포털이 XML 을 반환했습니다 (source=" + sourceCode
                    + ") — 인증키 유효성과 JSON 형식 파라미터(_type/resultType) 등록을 확인하세요");
        }
        try {
            return objectMapper.readTree(body.isEmpty() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("공공데이터포털 응답 JSON 파싱 실패 (source=" + sourceCode
                    + ") — 인증키/쿼터를 확인하세요", e);
        }
    }

    /** body.items.item(배열/단일 객체) 또는 body.items(배열) → 아이템 목록. */
    private List<JsonNode> extractItems(JsonNode body) {
        JsonNode itemsNode = body.path("items");
        JsonNode item = itemsNode.path("item");
        if (item.isArray()) {
            return toList(item);
        }
        if (item.isObject()) {
            return List.of(item);
        }
        if (itemsNode.isArray()) {
            return toList(itemsNode);
        }
        return List.of();
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    /** 1 아이템 → PortalItem. 객체가 아니면 skip(null). */
    private PortalItem toPortalItem(JsonNode item, List<String> keyFields) {
        if (!item.isObject()) {
            log.warn("객체가 아닌 아이템 skip: {}", item.getNodeType());
            return null;
        }
        String payload = item.toString();
        return new PortalItem(resolveKey(item, keyFields, payload), payload);
    }

    /** keyFields 값 조인 — 결측/과대 키는 payload SHA-256 으로 폴백해 멱등성을 지킨다. */
    private static String resolveKey(JsonNode item, List<String> keyFields, String payload) {
        if (!keyFields.isEmpty()) {
            List<String> values = new ArrayList<>(keyFields.size());
            for (String field : keyFields) {
                String value = item.path(field).asText("");
                if (value.isBlank()) {
                    values = null;
                    break;
                }
                values.add(value);
            }
            if (values != null) {
                String key = String.join(KEY_JOIN, values);
                if (key.length() <= MAX_KEY_LENGTH) {
                    return key;
                }
            }
        }
        return sha256(payload);
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
