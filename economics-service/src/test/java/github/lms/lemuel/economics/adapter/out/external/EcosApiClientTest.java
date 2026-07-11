package github.lms.lemuel.economics.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.economics.application.port.out.EcosClientPort.Observation;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * EcosApiClient — ECOS StatisticSearch 계약 파싱을 MockRestServiceServer 로 검증.
 * (URL/JSON 매핑, INFO-200, 오류 CODE, 결측 skip, 월별 정규화, 콤마 파싱, 잘못된 TIME skip)
 */
class EcosApiClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);
    private static final String DAILY_URI =
            "https://ecos.test/api/StatisticSearch/TESTKEY/json/kr/1/10000/722Y001/D/20260101/20260630/0101000";
    private static final String MONTHLY_URI =
            "https://ecos.test/api/StatisticSearch/TESTKEY/json/kr/1/10000/901Y009/M/202601/202606/0";

    private final Indicator baseRate = new Indicator("BASE_RATE", "한국은행 기준금리", "%",
            IndicatorCycle.D, "722Y001", "0101000", null);
    private final Indicator cpi = new Indicator("CPI", "소비자물가지수", "2020=100",
            IndicatorCycle.M, "901Y009", "0", null);

    private MockRestServiceServer server;
    private EcosApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EcosApiClient(
                new EcosProperties("TESTKEY", "https://ecos.test/api"), builder, new ObjectMapper());
    }

    @Test
    @DisplayName("정상 row 파싱 + 천단위 콤마 값 파싱")
    void parsesNormalRowsAndCommaValue() {
        server.expect(requestTo(DAILY_URI)).andRespond(withSuccess("""
                {"StatisticSearch":{"list_total_count":2,"row":[
                  {"TIME":"20260101","DATA_VALUE":"3.00"},
                  {"TIME":"20260102","DATA_VALUE":"1,384.5"}]}}
                """, MediaType.APPLICATION_JSON));

        List<Observation> obs = client.fetchObservations(baseRate, FROM, TO);

        assertThat(obs).hasSize(2);
        assertThat(obs.get(0)).isEqualTo(new Observation(LocalDate.of(2026, 1, 1), new BigDecimal("3.00")));
        assertThat(obs.get(1).observedDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(obs.get(1).value()).isEqualByComparingTo("1384.5");
        server.verify();
    }

    @Test
    @DisplayName("RESULT CODE=INFO-200(데이터 없음)은 빈 리스트")
    void info200ReturnsEmpty() {
        server.expect(requestTo(DAILY_URI)).andRespond(withSuccess(
                "{\"RESULT\":{\"CODE\":\"INFO-200\",\"MESSAGE\":\"해당하는 데이터가 없습니다.\"}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.fetchObservations(baseRate, FROM, TO)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("INFO-200 외 RESULT CODE 는 예외")
    void otherResultCodeThrows() {
        server.expect(requestTo(DAILY_URI)).andRespond(withSuccess(
                "{\"RESULT\":{\"CODE\":\"INFO-100\",\"MESSAGE\":\"인증키가 유효하지 않습니다.\"}}",
                MediaType.APPLICATION_JSON));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.fetchObservations(baseRate, FROM, TO))
                .withMessageContaining("INFO-100");
    }

    @Test
    @DisplayName("빈 문자열/\"-\" DATA_VALUE row 는 skip")
    void skipsEmptyAndDashValues() {
        server.expect(requestTo(DAILY_URI)).andRespond(withSuccess("""
                {"StatisticSearch":{"row":[
                  {"TIME":"20260101","DATA_VALUE":"-"},
                  {"TIME":"20260102","DATA_VALUE":""},
                  {"TIME":"20260103","DATA_VALUE":"2.55"}]}}
                """, MediaType.APPLICATION_JSON));

        List<Observation> obs = client.fetchObservations(baseRate, FROM, TO);

        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).observedDate()).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    @DisplayName("월별(M) TIME 은 해당 월 1일로 정규화")
    void monthlyTimeNormalizedToFirstDay() {
        server.expect(requestTo(MONTHLY_URI)).andRespond(withSuccess("""
                {"StatisticSearch":{"row":[{"TIME":"202606","DATA_VALUE":"114.5"}]}}
                """, MediaType.APPLICATION_JSON));

        List<Observation> obs = client.fetchObservations(cpi, FROM, TO);

        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).observedDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        server.verify();
    }

    @Test
    @DisplayName("네트워크 오류 시 예외 메시지에서 apiKey 를 마스킹한다(로그 유출 방지)")
    void masksApiKeyOnNetworkError() {
        // apiKey 가 URL 경로에 있어 I/O 오류 메시지에 전체 URL(키 포함)이 실리는 상황을 재현.
        server.expect(requestTo(DAILY_URI)).andRespond(withException(new IOException(
                "Connection refused for " + DAILY_URI)));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.fetchObservations(baseRate, FROM, TO))
                .withMessageContaining("statCode=722Y001")
                .withMessageNotContaining("TESTKEY");
    }

    @Test
    @DisplayName("파싱 불가한 TIME row 는 그 행만 skip, 전체를 죽이지 않음")
    void skipsInvalidTimeRow() {
        server.expect(requestTo(DAILY_URI)).andRespond(withSuccess("""
                {"StatisticSearch":{"row":[
                  {"TIME":"notadate","DATA_VALUE":"3.00"},
                  {"TIME":"20260102","DATA_VALUE":"3.10"}]}}
                """, MediaType.APPLICATION_JSON));

        List<Observation> obs = client.fetchObservations(baseRate, FROM, TO);

        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).observedDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    }
}
