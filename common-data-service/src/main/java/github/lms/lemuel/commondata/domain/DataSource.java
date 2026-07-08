package github.lms.lemuel.commondata.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 공공데이터포털(data.go.kr)에 등록된 OpenAPI 1개.
 *
 * <p>표준 data.go.kr 응답 봉투({@code response.header.resultCode} + {@code body.items.item[]})를
 * 따르는 API 라면 endpoint·기본 파라미터·자연키 필드만 등록해 코드 변경 없이 수집한다.
 *
 * @param defaultParams 호출 시 항상 붙는 쿼리 파라미터 — JSON 응답 형식 지정({@code _type}/
 *                      {@code resultType})이 API 마다 달라 여기서 소스별로 선언한다
 * @param keyFields     아이템의 자연키 필드명 목록 — 값을 {@code |} 로 조인해 recordKey 로 쓴다.
 *                      비어 있으면 payload SHA-256 해시가 대체(멱등 재수집의 기준)
 * @param pageSize      numOfRows — 기본 {@value #DEFAULT_PAGE_SIZE}, 상한 {@value #MAX_PAGE_SIZE}
 */
public record DataSource(Long id, String code, String name, String endpoint,
                         Map<String, String> defaultParams, List<String> keyFields,
                         int pageSize, boolean enabled, String description, Instant updatedAt) {

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 1000;

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    public DataSource {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "code 는 소문자·숫자·하이픈 2~50자여야 합니다: " + code);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은(는) 필수입니다");
        }
        if (endpoint == null
                || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            throw new IllegalArgumentException("endpoint 는 http(s) URL 이어야 합니다: " + endpoint);
        }
        defaultParams = defaultParams == null ? Map.of() : Map.copyOf(defaultParams);
        keyFields = keyFields == null ? List.of()
                : keyFields.stream()
                        .filter(f -> f != null && !f.isBlank())
                        .map(String::strip)
                        .toList();
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
    }
}
