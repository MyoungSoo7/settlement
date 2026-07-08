package github.lms.lemuel.commondata.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.application.port.in.GetDataRecordsUseCase;
import github.lms.lemuel.commondata.application.port.in.GetDataSourcesUseCase;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 데이터소스·수집 레코드 공개 조회 API.
 *
 * <p>전부 공공데이터포털 공개 데이터라 무인증(GET). 레코드 payload 는 JSON 원문을
 * 객체로 되살려 내려준다(이스케이프 문자열이 아닌 구조화 응답).
 */
@RestController
@RequestMapping("/api/common-data/sources")
public class DataSourceController {

    private final GetDataSourcesUseCase getDataSourcesUseCase;
    private final GetDataRecordsUseCase getDataRecordsUseCase;
    private final ObjectMapper objectMapper;

    public DataSourceController(GetDataSourcesUseCase getDataSourcesUseCase,
                                GetDataRecordsUseCase getDataRecordsUseCase,
                                ObjectMapper objectMapper) {
        this.getDataSourcesUseCase = getDataSourcesUseCase;
        this.getDataRecordsUseCase = getDataRecordsUseCase;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<SourceResponse> sources() {
        return getDataSourcesUseCase.getSources().stream()
                .map(SourceResponse::from)
                .toList();
    }

    @GetMapping("/{code}")
    public SourceResponse source(@PathVariable String code) {
        return SourceResponse.from(getDataSourcesUseCase.getSource(code));
    }

    @GetMapping("/{code}/records")
    public RecordsResponse records(@PathVariable String code,
                                   @RequestParam(defaultValue = "100") int limit) {
        List<RecordResponse> records = getDataRecordsUseCase.getRecords(code, limit).stream()
                .map(this::toResponse)
                .toList();
        return new RecordsResponse(code, records.size(), records);
    }

    private RecordResponse toResponse(DataRecord record) {
        return new RecordResponse(record.recordKey(), record.collectedAt(), parse(record.payload()));
    }

    /**
     * payload JSON 원문 → 순수 Java 타입(Map/List/String/Number).
     *
     * <p>Boot 4 의 웹 직렬화는 Jackson 3(tools.jackson)라 Jackson 2 의 JsonNode 를 반환하면
     * JSON 트리가 아닌 빈(bean)으로 직렬화된다 — 플랫폼 중립인 표준 컬렉션으로 되살려 내려준다.
     */
    private Object parse(String payload) {
        try {
            return objectMapper.readValue(payload, Object.class);
        } catch (Exception e) {
            return payload;   // 방어 — 저장 원문이 JSON 이 아니면 문자열 그대로
        }
    }

    // ----- 응답 DTO (컨트롤러 내부 record) -----

    record SourceResponse(String code, String name, String endpoint,
                          Map<String, String> defaultParams, List<String> keyFields,
                          int pageSize, boolean enabled, String description, Instant updatedAt) {
        static SourceResponse from(DataSource source) {
            return new SourceResponse(source.code(), source.name(), source.endpoint(),
                    source.defaultParams(), source.keyFields(), source.pageSize(),
                    source.enabled(), source.description(), source.updatedAt());
        }
    }

    record RecordsResponse(String sourceCode, int count, List<RecordResponse> records) { }

    record RecordResponse(String recordKey, Instant collectedAt, Object data) { }
}
