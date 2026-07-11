package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.domain.EconomicIndicatorSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * EconomicsIndicatorsApiClient — economics 공개 API 카탈로그 파싱을 MockRestServiceServer 로 검증
 * (최신값 매핑 · 값 없는 지표 제외 · change 결측 허용 · 오류 예외).
 */
class EconomicsIndicatorsApiClientTest {

    private static final String BASE = "http://economics.test";
    private static final String URI = BASE + "/api/economics/indicators";

    private MockRestServiceServer server;
    private EconomicsIndicatorsApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EconomicsIndicatorsApiClient(builder, new ObjectMapper(), BASE);
    }

    @Test
    @DisplayName("최신값 있는 지표만 스냅샷으로 매핑한다 (latest null 지표 제외, change 결측 허용)")
    void mapsSnapshots() {
        server.expect(requestTo(URI)).andRespond(withSuccess("""
                [
                  {"code":"BASE_RATE","name":"기준금리","unit":"%","cycle":"MONTHLY",
                   "latest":{"observedDate":"2026-06-30","value":"2.50"},
                   "change":{"amount":"-0.25","ratePercent":"-9.09"}},
                  {"code":"CPI","name":"소비자물가지수","unit":"2020=100","cycle":"MONTHLY",
                   "latest":null,"change":null}
                ]
                """, MediaType.APPLICATION_JSON));

        List<EconomicIndicatorSnapshot> snapshots = client.loadLatest();

        assertThat(snapshots).hasSize(1);
        EconomicIndicatorSnapshot s = snapshots.get(0);
        assertThat(s.code()).isEqualTo("BASE_RATE");
        assertThat(s.value()).isEqualByComparingTo("2.50");
        assertThat(s.observedDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(s.changeAmount()).isEqualByComparingTo("-0.25");
        server.verify();
    }

    @Test
    @DisplayName("change 가 없어도 스냅샷은 유효하다 (changeAmount null)")
    void allowsMissingChange() {
        server.expect(requestTo(URI)).andRespond(withSuccess("""
                [{"code":"USD_KRW","name":"원달러환율","unit":"원",
                  "latest":{"observedDate":"2026-07-09","value":"1350.5"}}]
                """, MediaType.APPLICATION_JSON));

        List<EconomicIndicatorSnapshot> snapshots = client.loadLatest();

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).changeAmount()).isNull();
    }

    @Test
    @DisplayName("서버 오류는 IllegalStateException — 서비스가 축 강등으로 처리한다")
    void throwsOnServerError() {
        server.expect(requestTo(URI)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatIllegalStateException().isThrownBy(client::loadLatest)
                .withMessageContaining("economics API 오류");
    }
}
