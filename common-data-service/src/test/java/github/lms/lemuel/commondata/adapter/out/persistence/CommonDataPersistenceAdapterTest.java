package github.lms.lemuel.commondata.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonDataPersistenceAdapterTest {

    private DataSourceRepository sourceRepository;
    private DataRecordRepository recordRepository;
    private CommonDataPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(DataSourceRepository.class);
        recordRepository = mock(DataRecordRepository.class);
        adapter = new CommonDataPersistenceAdapter(sourceRepository, recordRepository, new ObjectMapper());
    }

    private static DataSourceJpaEntity entity(String code, String defaultParams, String keyFields) {
        DataSourceJpaEntity e = DataSourceJpaEntity.create(code);
        e.apply("특일정보", "https://apis.data.go.kr/x", defaultParams, keyFields, 100, true, "설명");
        return e;
    }

    @Test
    void findAll_은_코드순으로_도메인변환한다() {
        when(sourceRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of(entity("kasi-rest-days", "{\"_type\":\"json\"}", "locdate")));

        List<DataSource> all = adapter.findAll();

        assertThat(all).hasSize(1);
        DataSource s = all.get(0);
        assertThat(s.code()).isEqualTo("kasi-rest-days");
        assertThat(s.defaultParams()).containsEntry("_type", "json");
        assertThat(s.keyFields()).containsExactly("locdate");
    }

    @Test
    void findByCode_는_없으면_empty() {
        when(sourceRepository.findByCode("nope")).thenReturn(Optional.empty());
        assertThat(adapter.findByCode("nope")).isEmpty();
    }

    @Test
    void 빈_params_와_null_keyFields_도_변환된다() {
        when(sourceRepository.findByCode("src-a")).thenReturn(Optional.of(entity("src-a", null, null)));

        DataSource s = adapter.findByCode("src-a").orElseThrow();

        assertThat(s.defaultParams()).isEmpty();
        assertThat(s.keyFields()).isEmpty();
    }

    @Test
    void 잘못된_params_JSON은_예외() {
        when(sourceRepository.findByCode("bad")).thenReturn(Optional.of(entity("bad", "not-json", "locdate")));

        assertThatThrownBy(() -> adapter.findByCode("bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default_params JSON 파싱 실패");
    }

    @Test
    void upsert_소스_신규는_create후_저장() {
        when(sourceRepository.findByCode("src-a")).thenReturn(Optional.empty());
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DataSource result = adapter.upsert(new DataSource(null, "src-a", "이름", "https://x.test",
                Map.of("_type", "json"), List.of("locdate"), 100, true, "설명", null));

        assertThat(result.code()).isEqualTo("src-a");
        ArgumentCaptor<DataSourceJpaEntity> captor = ArgumentCaptor.forClass(DataSourceJpaEntity.class);
        verify(sourceRepository).save(captor.capture());
        assertThat(captor.getValue().getEndpoint()).isEqualTo("https://x.test");
    }

    @Test
    void upsert_소스_기존은_apply후_저장() {
        DataSourceJpaEntity existing = entity("src-a", "{}", null);
        when(sourceRepository.findByCode("src-a")).thenReturn(Optional.of(existing));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adapter.upsert(new DataSource(1L, "src-a", "새이름", "https://x.test",
                Map.of(), List.of(), 200, false, null, null));

        verify(sourceRepository).save(existing);
        assertThat(existing.getName()).isEqualTo("새이름");
        assertThat(existing.getPageSize()).isEqualTo(200);
    }

    @Test
    void findLatest_는_소스없으면_빈리스트() {
        when(sourceRepository.findByCode("nope")).thenReturn(Optional.empty());
        assertThat(adapter.findLatest("nope", 10)).isEmpty();
    }

    @Test
    void findLatest_는_레코드를_도메인변환한다() {
        DataSourceJpaEntity src = entity("src-a", "{}", null);
        setId(src, 7L);
        when(sourceRepository.findByCode("src-a")).thenReturn(Optional.of(src));
        DataRecordJpaEntity rec = DataRecordJpaEntity.create(7L, "20260101");
        rec.apply("{\"dateName\":\"신정\"}", Instant.now());
        when(recordRepository.findBySourceIdOrderByCollectedAtDescIdDesc(eq(7L), any()))
                .thenReturn(List.of(rec));

        List<DataRecord> records = adapter.findLatest("src-a", 10);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).recordKey()).isEqualTo("20260101");
        assertThat(records.get(0).sourceCode()).isEqualTo("src-a");
    }

    @Test
    void upsert_레코드_신규는_create후_저장() {
        DataSourceJpaEntity src = entity("src-a", "{}", null);
        setId(src, 7L);
        when(sourceRepository.findByCode("src-a")).thenReturn(Optional.of(src));
        when(recordRepository.findBySourceIdAndRecordKey(7L, "20260101")).thenReturn(Optional.empty());

        adapter.upsert(new DataRecord(null, "src-a", "20260101", "{\"a\":1}", Instant.now()));

        verify(recordRepository).save(any(DataRecordJpaEntity.class));
    }

    @Test
    void upsert_레코드_기존은_apply후_저장() {
        DataSourceJpaEntity src = entity("src-a", "{}", null);
        setId(src, 7L);
        when(sourceRepository.findByCode("src-a")).thenReturn(Optional.of(src));
        DataRecordJpaEntity existing = DataRecordJpaEntity.create(7L, "20260101");
        existing.apply("{\"old\":1}", Instant.now());
        when(recordRepository.findBySourceIdAndRecordKey(7L, "20260101")).thenReturn(Optional.of(existing));

        adapter.upsert(new DataRecord(null, "src-a", "20260101", "{\"new\":2}", null));

        verify(recordRepository).save(existing);
        assertThat(existing.getPayload()).isEqualTo("{\"new\":2}");
    }

    @Test
    void upsert_레코드_소스없으면_예외() {
        when(sourceRepository.findByCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.upsert(
                new DataRecord(null, "nope", "k", "{\"a\":1}", Instant.now())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("데이터소스가 없습니다");
    }

    /** IDENTITY 생성 id 를 리플렉션으로 주입(단위 테스트는 DB 없이 도메인 매핑만 검증). */
    private static void setId(DataSourceJpaEntity entity, Long id) {
        try {
            var f = DataSourceJpaEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
