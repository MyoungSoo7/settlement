package github.lms.lemuel.commondata.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.application.port.out.LoadDataRecordPort;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataRecordPort;
import github.lms.lemuel.commondata.application.port.out.SaveDataSourcePort;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 데이터소스/레코드 영속성 어댑터.
 *
 * <p>도메인의 defaultParams(Map)·keyFields(List)를 컬럼(JSON 문자열·CSV)으로 오가는 변환을
 * 여기서 담당한다 — 도메인·엔티티는 서로를 모른다.
 */
@Component
public class CommonDataPersistenceAdapter
        implements LoadDataSourcePort, SaveDataSourcePort, LoadDataRecordPort, SaveDataRecordPort {

    private static final TypeReference<Map<String, String>> PARAMS_TYPE = new TypeReference<>() { };

    private final DataSourceRepository dataSourceRepository;
    private final DataRecordRepository dataRecordRepository;
    private final ObjectMapper objectMapper;

    public CommonDataPersistenceAdapter(DataSourceRepository dataSourceRepository,
                                        DataRecordRepository dataRecordRepository,
                                        ObjectMapper objectMapper) {
        this.dataSourceRepository = dataSourceRepository;
        this.dataRecordRepository = dataRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataSource> findAll() {
        return dataSourceRepository.findAllByOrderByCodeAsc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DataSource> findByCode(String code) {
        return dataSourceRepository.findByCode(code).map(this::toDomain);
    }

    @Override
    @Transactional
    public DataSource upsert(DataSource source) {
        DataSourceJpaEntity entity = dataSourceRepository.findByCode(source.code())
                .orElseGet(() -> DataSourceJpaEntity.create(source.code()));
        entity.apply(source.name(), source.endpoint(), writeParams(source.defaultParams()),
                source.keyFields().isEmpty() ? null : String.join(",", source.keyFields()),
                source.pageSize(), source.enabled(), source.description());
        return toDomain(dataSourceRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataRecord> findLatest(String sourceCode, int limit) {
        return dataSourceRepository.findByCode(sourceCode)
                .map(source -> dataRecordRepository
                        .findBySourceIdOrderByCollectedAtDescIdDesc(source.getId(), Limit.of(limit))
                        .stream()
                        .map(record -> toDomain(record, sourceCode))
                        .toList())
                .orElseGet(List::of);
    }

    @Override
    @Transactional
    public void upsert(DataRecord record) {
        DataSourceJpaEntity source = dataSourceRepository.findByCode(record.sourceCode())
                .orElseThrow(() -> new IllegalStateException(
                        "레코드가 참조하는 데이터소스가 없습니다: " + record.sourceCode()));
        DataRecordJpaEntity entity = dataRecordRepository
                .findBySourceIdAndRecordKey(source.getId(), record.recordKey())
                .orElseGet(() -> DataRecordJpaEntity.create(source.getId(), record.recordKey()));
        entity.apply(record.payload(), record.collectedAt());
        dataRecordRepository.save(entity);
    }

    // ---- 변환 ----

    private DataSource toDomain(DataSourceJpaEntity entity) {
        return new DataSource(entity.getId(), entity.getCode(), entity.getName(),
                entity.getEndpoint(), readParams(entity.getDefaultParams()),
                splitKeyFields(entity.getKeyFields()), entity.getPageSize(), entity.isEnabled(),
                entity.getDescription(), entity.getUpdatedAt());
    }

    private static DataRecord toDomain(DataRecordJpaEntity entity, String sourceCode) {
        return new DataRecord(entity.getId(), sourceCode, entity.getRecordKey(),
                entity.getPayload(), entity.getCollectedAt());
    }

    private static List<String> splitKeyFields(String keyFields) {
        return keyFields == null || keyFields.isBlank()
                ? List.of()
                : Arrays.stream(keyFields.split(",")).map(String::strip).toList();
    }

    private Map<String, String> readParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, PARAMS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("default_params JSON 파싱 실패: " + json, e);
        }
    }

    private String writeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("default_params 직렬화 실패", e);
        }
    }
}
