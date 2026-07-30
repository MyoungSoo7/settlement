package github.lms.lemuel.loan.adapter.out.external;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨슈머측 REST 계약 테스트 — common-data {@code /api/common-data/sources/{code}/records} 응답의
 * 정본 샘플(shared-common testFixtures {@code contracts/internal-rest/common-data/})을
 * {@link SatelliteCollateralValuationAdapter} 의 record 로 역직렬화할 수 있어야 한다.
 *
 * <p><b>왜 필요한가</b>: 이 어댑터의 record 가 프로듀서 계약과 어긋나 있었다 —
 * 프로듀서는 {@code sourceCode}/{@code data}/{@code Instant} 인데 컨슈머는
 * {@code code}/{@code payload}/{@code LocalDateTime} 이라고 지레짐작했다. 그 결과 프로듀서가
 * 정상 응답해도 거래금액이 한 번도 읽히지 않고 신청인 제시값으로 조용히 폴백해, 담보가
 * 과소평가된 주택담보대출이 승인될 수 있었다. 기존 테스트는 실제 계약이 아니라 그 지레짐작한
 * 스키마를 목킹해서 드리프트를 못 잡았다.
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} 기본값(켜짐)에 의존한다 — 샘플에 있는 필드가 record 에
 * 없으면 실패하므로, 필드 개명·제거 드리프트가 빌드 시점에 드러난다.
 */
@DisplayName("common-data /records REST 계약 — 컨슈머(담보평가 어댑터 record)측")
class CommonDataRecordsRestContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private static SatelliteCollateralValuationAdapter.RecordsDto readSample() {
        try (InputStream in = CommonDataRecordsRestContractTest.class.getResourceAsStream(
                "/contracts/internal-rest/common-data/records.sample.json")) {
            assertThat(in).as("정본 샘플 존재").isNotNull();
            return MAPPER.readValue(in, SatelliteCollateralValuationAdapter.RecordsDto.class);
        } catch (IOException e) {
            throw new AssertionError("계약 샘플 역직렬화 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data) {
        return (Map<String, Object>) data;
    }

    @Test
    @DisplayName("정본 샘플이 어댑터 record 로 읽히고 모든 필드가 채워진다")
    void canonicalSampleDeserializes() {
        SatelliteCollateralValuationAdapter.RecordsDto dto = readSample();

        assertThat(dto.sourceCode()).isEqualTo("molit-apt-trade");
        assertThat(dto.count()).isEqualTo(2);
        assertThat(dto.records()).hasSize(2);

        SatelliteCollateralValuationAdapter.RecordDto first = dto.records().get(0);
        assertThat(first.recordKey()).isEqualTo("11680-래미안-2026-05");
        // collectedAt 이 Instant 여야 한다 — LocalDateTime 이면 'Z' 때문에 역직렬화 자체가 깨진다.
        assertThat(first.collectedAt()).isNotNull();
        // data(구 payload) 가 채워져야 실거래가가 실제로 쓰인다. null 이면 조용히 제시값 폴백이다.
        assertThat(first.data()).isInstanceOf(Map.class);
        Map<String, Object> data = asMap(first.data());
        assertThat(data).containsEntry("dealAmount", "79,000");
    }
}
