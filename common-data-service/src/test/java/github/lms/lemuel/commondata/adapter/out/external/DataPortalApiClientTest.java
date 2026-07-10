package github.lms.lemuel.commondata.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort.PortalItem;
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
 * 공공데이터포털 표준 봉투 클라이언트 — MockRestServiceServer 로 items 형태(배열/단건/최상위 배열),
 * NODATA·오류·XML·JSON파싱실패, 키 해석(자연키/해시 폴백) 분기를 검증한다.
 */
class DataPortalApiClientTest {

    private static final String ENDPOINT =
            "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";

    private MockRestServiceServer server;

    private DataPortalApiClient clientWith(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new DataPortalApiClient(new DataPortalProperties(apiKey), builder, new ObjectMapper());
    }

    private static DataSource source(List<String> keyFields, int pageSize) {
        return new DataSource(1L, "kasi-rest-days", "특일정보", ENDPOINT,
                Map.of("_type", "json"), keyFields, pageSize, true, "공휴일", null);
    }

    private static String envelope(String totalCount, String itemsJson) {
        String tc = totalCount == null ? "" : "\"totalCount\":" + totalCount + ",";
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},"
                + "\"body\":{" + tc + "\"items\":" + itemsJson + "}}}";
    }

    @Test
    void isConfigured_는_키설정여부를_반영한다() {
        assertThat(clientWith("").isConfigured()).isFalse();
        assertThat(clientWith("KEY").isConfigured()).isTrue();
    }

    @Test
    void item_배열을_페이지네이션으로_수집한다() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(envelope("2",
                        "{\"item\":[{\"dateName\":\"신정\",\"locdate\":\"20260101\"}]}"), APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=2")))
                .andRespond(withSuccess(envelope("2",
                        "{\"item\":[{\"dateName\":\"설날\",\"locdate\":\"20260217\"}]}"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(source(List.of("locdate"), 1), Map.of());

        assertThat(items).hasSize(2);
        assertThat(items.get(0).recordKey()).isEqualTo("20260101");
        assertThat(items.get(0).payloadJson()).contains("신정");
        assertThat(items.get(1).recordKey()).isEqualTo("20260217");
        server.verify();
    }

    @Test
    void item_단건객체도_지원한다() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(envelope(null,
                        "{\"item\":{\"dateName\":\"신정\",\"locdate\":\"20260101\"}}"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(source(List.of("locdate"), 100), null);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).recordKey()).isEqualTo("20260101");
    }

    @Test
    void items_가_최상위_배열이어도_지원한다() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(envelope(null,
                        "[{\"dateName\":\"신정\",\"locdate\":\"20260101\"}]"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(source(List.of("locdate"), 100), Map.of());

        assertThat(items).hasSize(1);
    }

    @Test
    void 여러_자연키_조인과_비객체_아이템_스킵() {
        DataPortalApiClient client = clientWith("KEY");
        // 두번째 아이템은 문자열(비객체) → skip, 세번째는 키필드 결측 → payload 해시 폴백
        String itemsJson = "{\"item\":["
                + "{\"dateName\":\"신정\",\"locdate\":\"20260101\"},"
                + "\"not-an-object\","
                + "{\"dateName\":\"삼일절\"}"
                + "]}";
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(envelope("3", itemsJson), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(source(List.of("dateName", "locdate"), 100), Map.of());

        assertThat(items).hasSize(2);
        assertThat(items.get(0).recordKey()).isEqualTo("신정|20260101");   // 조인 키
        assertThat(items.get(1).recordKey()).hasSize(64);                   // SHA-256 hex 폴백
    }

    @Test
    void 키필드_없으면_payload_해시가_키() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(envelope(null,
                        "{\"item\":{\"dateName\":\"신정\"}}"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(source(List.of(), 100), Map.of());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).recordKey()).hasSize(64);
    }

    @Test
    void 과대한_자연키는_해시로_폴백() {
        DataPortalApiClient client = clientWith("KEY");
        String big = "x".repeat(400);
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(envelope(null,
                        "{\"item\":{\"locdate\":\"" + big + "\"}}"), APPLICATION_JSON));

        List<PortalItem> items = client.fetchItems(source(List.of("locdate"), 100), Map.of());

        assertThat(items.get(0).recordKey()).hasSize(64);   // 300자 초과 → 해시
    }

    @Test
    void NODATA_03이면_빈리스트() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NODATA\"}}}",
                        APPLICATION_JSON));

        assertThat(client.fetchItems(source(List.of("locdate"), 100), Map.of())).isEmpty();
    }

    @Test
    void 빈_items면_중단() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(envelope("0", "{}"), APPLICATION_JSON));

        assertThat(client.fetchItems(source(List.of("locdate"), 100), Map.of())).isEmpty();
    }

    @Test
    void 오류코드는_예외() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"LIMITED\"}}}",
                        APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchItems(source(List.of("locdate"), 100), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resultCode=22");
    }

    @Test
    void XML_응답은_인증키오류로_예외() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess("<OpenAPI_ServiceResponse><cmmMsgHeader/></OpenAPI_ServiceResponse>",
                        APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchItems(source(List.of("locdate"), 100), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void JSON_파싱실패는_예외() {
        DataPortalApiClient client = clientWith("KEY");
        server.expect(requestTo(containsString("getRestDeInfo")))
                .andRespond(withSuccess("@@@nope@@@", APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchItems(source(List.of("locdate"), 100), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 파싱 실패");
    }
}
