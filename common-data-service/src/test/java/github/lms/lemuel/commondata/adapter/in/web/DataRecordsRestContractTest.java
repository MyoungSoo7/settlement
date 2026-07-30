package github.lms.lemuel.commondata.adapter.in.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로듀서측 REST 계약 테스트 — {@code /api/common-data/sources/{code}/records} 응답 record 가
 * 정본 샘플(shared-common testFixtures {@code contracts/internal-rest/common-data/})과 일치해야 한다.
 *
 * <p>컨슈머(loan-service 담보평가 어댑터)의 동명 테스트와 <b>같은 샘플</b>을 각자의 record 로 읽는다.
 * 어느 한쪽이 필드를 개명·제거하면 반대편 테스트가 빌드 시점에 깨진다.
 *
 * <p>이 계약이 없던 동안 컨슈머가 {@code sourceCode}/{@code data} 를 {@code code}/{@code payload} 로
 * 지레짐작해, 프로듀서가 정상 응답해도 실거래가가 한 번도 읽히지 않고 신청인 제시값으로 조용히
 * 폴백했다(담보 과소평가 위험). 양측 테스트가 같은 샘플을 보는 것이 재발 방지 장치다.
 */
@DisplayName("/api/common-data/sources/{code}/records REST 계약 — 프로듀서측")
class DataRecordsRestContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    @DisplayName("정본 샘플 ↔ RecordsResponse record 호환")
    void recordsSampleMatchesResponseRecord() {
        DataSourceController.RecordsResponse v =
                read("records.sample.json", DataSourceController.RecordsResponse.class);

        assertThat(v.sourceCode()).isEqualTo("molit-apt-trade");
        assertThat(v.count()).isEqualTo(2);
        assertThat(v.records()).hasSize(2);

        DataSourceController.RecordResponse first = v.records().get(0);
        assertThat(first.recordKey()).isEqualTo("11680-래미안-2026-05");
        assertThat(first.collectedAt()).isNotNull();
        assertThat(first.data()).isNotNull();
    }

    private static <T> T read(String sample, Class<T> type) {
        try (InputStream in = DataRecordsRestContractTest.class.getResourceAsStream(
                "/contracts/internal-rest/common-data/" + sample)) {
            assertThat(in).as("정본 샘플 %s 존재", sample).isNotNull();
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new AssertionError("계약 샘플 역직렬화 실패: " + sample, e);
        }
    }
}
