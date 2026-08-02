package github.lms.lemuel.commondata.adapter.out.external;

import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort.PortalItem;
import github.lms.lemuel.commondata.domain.DataProvider;
import github.lms.lemuel.commondata.domain.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 서울 열린데이터광장 봉투 클라이언트 — 경로형 URL(키/json/서비스/START/END/후행경로),
 * 표준 봉투(서비스명 키 + RESULT.CODE + row[])와 citydata 변형(플랫 RESULT.CODE + 임의 배열 키),
 * INFO-200(데이터없음)·오류코드·XML 방어, START/END 페이지네이션을 검증한다.
 */
class SeoulOpenApiClientTest {

    private static final String ENDPOINT = "http://openapi.seoul.go.kr:8088";

    private MockRestServiceServer server;

    private SeoulOpenApiClient clientWith(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new SeoulOpenApiClient(new SeoulOpenApiProperties(apiKey), builder,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static DataSource source(Map<String, String> params, List<String> keyFields, int pageSize) {
        return new DataSource(1L, "seoul-living-pop", "서울 생활인구", ENDPOINT,
                DataProvider.SEOUL_OPENAPI, params, keyFields, pageSize, true, null, null);
    }

    /** 표준 봉투: {서비스명:{list_total_count, RESULT:{CODE}, row:[..]}} */
    private static String envelope(String service, int totalCount, String rowsJson) {
        return "{\"" + service + "\":{\"list_total_count\":" + totalCount + ","
                + "\"RESULT\":{\"CODE\":\"INFO-000\",\"MESSAGE\":\"정상 처리되었습니다\"},"
                + "\"row\":" + rowsJson + "}}";
    }

    @Test
    void isConfigured_는_키설정여부를_반영한다() {
        assertThat(clientWith("").isConfigured()).isFalse();
        assertThat(clientWith("SEOULKEY").isConfigured()).isTrue();
        assertThat(clientWith("SEOULKEY").provider()).isEqualTo(DataProvider.SEOUL_OPENAPI);
    }

    @Test
    void 표준봉투_row_배열을_수집한다__URL은_키_json_서비스_START_END() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/SEOULKEY/json/SPOP_LOCL_RESD_DONG/1/100")))
                .andRespond(withSuccess(envelope("SPOP_LOCL_RESD_DONG", 2,
                        "[{\"STDR_DE_ID\":\"20260801\",\"ADSTRD_CODE_SE\":\"11110515\"},"
                                + "{\"STDR_DE_ID\":\"20260801\",\"ADSTRD_CODE_SE\":\"11110530\"}]"),
                        APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(
                source(Map.of("service", "SPOP_LOCL_RESD_DONG"),
                        List.of("STDR_DE_ID", "ADSTRD_CODE_SE"), 100), Map.of());

        assertThat(items).hasSize(2);
        assertThat(items.get(0).recordKey()).isEqualTo("20260801|11110515");
        assertThat(items.get(0).payloadJson()).contains("11110515");
        server.verify();
    }

    @Test
    void list_total_count_기준_START_END_페이지네이션() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/json/SPOP_LOCL_RESD_DONG/1/2")))
                .andRespond(withSuccess(envelope("SPOP_LOCL_RESD_DONG", 3,
                        "[{\"K\":\"a\"},{\"K\":\"b\"}]"), APPLICATION_JSON));
        server.expect(requestTo(containsString("/json/SPOP_LOCL_RESD_DONG/3/4")))
                .andRespond(withSuccess(envelope("SPOP_LOCL_RESD_DONG", 3,
                        "[{\"K\":\"c\"}]"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(
                source(Map.of("service", "SPOP_LOCL_RESD_DONG"), List.of("K"), 2), Map.of());

        assertThat(items).hasSize(3);
        assertThat(items.get(2).recordKey()).isEqualTo("c");
        server.verify();
    }

    @Test
    void path_후행경로는_URL_세그먼트로_붙고_override_로_교체가능() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/json/citydata_ppltn/1/5/POI009")))
                .andRespond(withSuccess(envelope("citydata_ppltn", 1,
                        "[{\"AREA_CD\":\"POI009\",\"PPLTN_TIME\":\"2026-08-02 12:00\"}]"),
                        APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(
                source(Map.of("service", "citydata_ppltn", "path", "POI001"),
                        List.of("AREA_CD", "PPLTN_TIME"), 5),
                Map.of("path", "POI009"));   // override 가 defaultParams.path 를 교체

        assertThat(items).hasSize(1);
        assertThat(items.get(0).recordKey()).isEqualTo("POI009|2026-08-02 12:00");
        server.verify();
    }

    @Test
    void citydata_변형__서비스명_불일치_배열키와_중첩_점포함_RESULT_CODE_지원() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        // 실측 봉투(2026-08-02 광화문·덕수궁): root 에 "SeoulRtd.citydata_ppltn" 배열 +
        // RESULT 객체 안에 점 포함 키("RESULT.CODE"/"RESULT.MESSAGE")가 온다
        String body = "{\"SeoulRtd.citydata_ppltn\":["
                + "{\"AREA_CD\":\"POI009\",\"AREA_CONGEST_LVL\":\"여유\",\"PPLTN_TIME\":\"2026-08-02 17:05\","
                + "\"FCST_PPLTN\":[{\"FCST_TIME\":\"2026-08-02 18:00\",\"FCST_CONGEST_LVL\":\"보통\"}]}],"
                + "\"RESULT\":{\"RESULT.CODE\":\"INFO-000\",\"RESULT.MESSAGE\":\"정상 처리되었습니다.\"}}";
        server.expect(requestTo(containsString("/json/citydata_ppltn/1/5")))
                .andRespond(withSuccess(body, APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(
                source(Map.of("service", "citydata_ppltn"), List.of("AREA_CD"), 5), Map.of());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).recordKey()).isEqualTo("POI009");
        assertThat(items.get(0).payloadJson()).contains("FCST_PPLTN");   // 12시간 예측 원문 보존
    }

    @Test
    void citydata_변형__플랫_루트_RESULT_CODE_도_지원() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        String body = "{\"SeoulRtd.citydata_ppltn\":[{\"AREA_CD\":\"POI009\"}],"
                + "\"RESULT.CODE\":\"INFO-000\",\"RESULT.MESSAGE\":\"정상 처리되었습니다\"}";
        server.expect(requestTo(containsString("/json/citydata_ppltn/1/5")))
                .andRespond(withSuccess(body, APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(
                source(Map.of("service", "citydata_ppltn"), List.of("AREA_CD"), 5), Map.of());

        assertThat(items).hasSize(1);
    }

    @Test
    void INFO_200_데이터없음은_빈리스트() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/json/SPOP_LOCL_RESD_DONG/")))
                .andRespond(withSuccess(
                        "{\"RESULT\":{\"CODE\":\"INFO-200\",\"MESSAGE\":\"해당하는 데이터가 없습니다.\"}}",
                        APPLICATION_JSON));

        assertThat(client.fetchItems(
                source(Map.of("service", "SPOP_LOCL_RESD_DONG"), List.of(), 100), Map.of()))
                .isEmpty();
    }

    @Test
    void 오류코드는_예외() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/json/SPOP_LOCL_RESD_DONG/")))
                .andRespond(withSuccess(
                        "{\"RESULT\":{\"CODE\":\"INFO-100\",\"MESSAGE\":\"인증키가 유효하지 않습니다.\"}}",
                        APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchItems(
                source(Map.of("service", "SPOP_LOCL_RESD_DONG"), List.of(), 100), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INFO-100");
    }

    @Test
    void XML_응답은_명시적_예외() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/json/SPOP_LOCL_RESD_DONG/")))
                .andRespond(withSuccess("<RESULT><CODE>ERROR-500</CODE></RESULT>", APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchItems(
                source(Map.of("service", "SPOP_LOCL_RESD_DONG"), List.of(), 100), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void service_파라미터_미등록이면_예외() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");

        assertThatThrownBy(() -> client.fetchItems(source(Map.of(), List.of(), 100), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service");
    }

    @Test
    void 키필드_결측_아이템은_payload_해시로_폴백() {
        SeoulOpenApiClient client = clientWith("SEOULKEY");
        server.expect(requestTo(containsString("/json/SPOP_LOCL_RESD_DONG/")))
                .andRespond(withSuccess(envelope("SPOP_LOCL_RESD_DONG", 1,
                        "[{\"OTHER\":\"x\"}]"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(
                source(Map.of("service", "SPOP_LOCL_RESD_DONG"), List.of("STDR_DE_ID"), 100),
                Map.of());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).recordKey()).hasSize(64);   // SHA-256 hex 폴백
    }
}
