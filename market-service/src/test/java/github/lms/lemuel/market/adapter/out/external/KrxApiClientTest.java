package github.lms.lemuel.market.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.market.application.port.out.KrxClientPort.StockPrice;
import github.lms.lemuel.market.domain.Market;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * 금융위 주식시세정보 클라이언트 — MockRestServiceServer 로 HTTP 응답을 흉내내
 * 페이지네이션·NODATA·오류코드·행 파싱/스킵 분기를 검증한다.
 */
class KrxApiClientTest {

    private static final LocalDate BASE = LocalDate.of(2026, 7, 7);

    private MockRestServiceServer server;

    private KrxApiClient clientWith(KrxProperties props) {
        // 간격 0 = 대기 없음. 파싱·페이지네이션 검증에 실제 sleep 을 섞으면 스위트만 느려진다.
        return clientWith(props, 0L);
    }

    private KrxApiClient clientWith(KrxProperties props, long requestIntervalMs) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new KrxApiClient(props, builder, new ObjectMapper(), requestIntervalMs);
    }

    private static KrxProperties props(String key, int pageSize) {
        return new KrxProperties(key, "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService", pageSize);
    }

    private static String okBody(int totalCount, String itemsJson) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},"
                + "\"body\":{\"totalCount\":" + totalCount + ",\"items\":{\"item\":" + itemsJson + "}}}}";
    }

    private static String item(String code, String market, String close) {
        return "{\"srtnCd\":\"" + code + "\",\"isinCd\":\"KR" + code + "\",\"itmsNm\":\"종목" + code
                + "\",\"mrktCtg\":\"" + market + "\",\"clpr\":\"" + close + "\",\"mkp\":\"1,000\","
                + "\"hipr\":\"1100\",\"lopr\":\"900\",\"vs\":\"10\",\"fltRt\":\"1.1\","
                + "\"trqu\":\"5000\",\"trPrc\":\"5000000\",\"lstgStCnt\":\"1000000\",\"mrktTotAmt\":\"78000000\"}";
    }

    @Test
    void isConfigured_는_키설정_여부를_반영한다() {
        assertThat(clientWith(props("", 1000)).isConfigured()).isFalse();
        assertThat(clientWith(props("KEY", 1000)).isConfigured()).isTrue();
    }

    @Test
    void 여러_페이지를_순회해_전_종목을_수집한다() {
        KrxApiClient client = clientWith(props("KEY", 1));
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(okBody(2, "[" + item("005930", "KOSPI", "78000") + "]"), APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=2")))
                .andRespond(withSuccess(okBody(2, "[" + item("000660", "KOSDAQ", "180000") + "]"), APPLICATION_JSON));

        List<StockPrice> prices = client.fetchQuotes(BASE);

        assertThat(prices).hasSize(2);
        StockPrice first = prices.get(0);
        assertThat(first.stockCode()).isEqualTo("005930");
        assertThat(first.market()).isEqualTo(Market.KOSPI);
        assertThat(first.closePrice()).isEqualByComparingTo("78000");
        assertThat(first.openPrice()).isEqualByComparingTo("1000");   // "1,000" 콤마 제거
        assertThat(first.volume().toString()).isEqualTo("5000");
        assertThat(prices.get(1).market()).isEqualTo(Market.KOSDAQ);
        server.verify();
    }

    @Test
    void 페이지_사이에_요청_간격을_둔다() {
        // 2026-08-20 고아 파라미터 감사: app.market.sync.request-interval-ms 는 yml 에 "쿼터 보호용"
        // 이라 적혀 있었지만 읽는 코드가 없어 실제로는 무간격 연타였다. 값이 동작을 바꾸는지 못 박는다.
        long intervalMs = 120;
        KrxApiClient client = clientWith(props("KEY", 1), intervalMs);
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(okBody(3, "[" + item("005930", "KOSPI", "78000") + "]"), APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=2")))
                .andRespond(withSuccess(okBody(3, "[" + item("000660", "KOSDAQ", "180000") + "]"), APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=3")))
                .andRespond(withSuccess(okBody(3, "[" + item("035720", "KOSPI", "55000") + "]"), APPLICATION_JSON));

        long startedAt = System.nanoTime();
        List<StockPrice> prices = client.fetchQuotes(BASE);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(prices).hasSize(3);
        // 3 페이지 = 간격 2 회(첫 호출은 지연 없음). 스케줄러 지터로 아래로 새지 않게 여유를 둔다.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2 * intervalMs - 20);
        server.verify();
    }

    @Test
    void 요청_간격_0_이면_대기하지_않는다() {
        KrxApiClient client = clientWith(props("KEY", 1), 0L);
        server.expect(requestTo(containsString("pageNo=1")))
                .andRespond(withSuccess(okBody(2, "[" + item("005930", "KOSPI", "78000") + "]"), APPLICATION_JSON));
        server.expect(requestTo(containsString("pageNo=2")))
                .andRespond(withSuccess(okBody(2, "[" + item("000660", "KOSDAQ", "180000") + "]"), APPLICATION_JSON));

        long startedAt = System.nanoTime();
        assertThat(client.fetchQuotes(BASE)).hasSize(2);
        assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(1_000);
        server.verify();
    }

    @Test
    void NODATA_03_이면_빈_리스트() {
        KrxApiClient client = clientWith(props("KEY", 1000));
        server.expect(requestTo(containsString("getStockPriceInfo")))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NODATA\"}}}",
                        APPLICATION_JSON));

        assertThat(client.fetchQuotes(BASE)).isEmpty();
    }

    @Test
    void totalCount_0_이면_빈_리스트() {
        KrxApiClient client = clientWith(props("KEY", 1000));
        server.expect(requestTo(containsString("getStockPriceInfo")))
                .andRespond(withSuccess(okBody(0, "[]"), APPLICATION_JSON));

        assertThat(client.fetchQuotes(BASE)).isEmpty();
    }

    @Test
    void items_가_배열이_아니면_중단() {
        KrxApiClient client = clientWith(props("KEY", 1000));
        server.expect(requestTo(containsString("getStockPriceInfo")))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                                + "\"body\":{\"totalCount\":5,\"items\":\"\"}}}", APPLICATION_JSON));

        assertThat(client.fetchQuotes(BASE)).isEmpty();
    }

    @Test
    void 오류코드는_예외() {
        KrxApiClient client = clientWith(props("KEY", 1000));
        server.expect(requestTo(containsString("getStockPriceInfo")))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"99\",\"resultMsg\":\"SERVICE ERROR\"}}}",
                        APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchQuotes(BASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resultCode=99");
    }

    @Test
    void JSON_파싱_실패는_예외() {
        KrxApiClient client = clientWith(props("KEY", 1000));
        server.expect(requestTo(containsString("getStockPriceInfo")))
                .andRespond(withSuccess("@@@invalid@@@", APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchQuotes(BASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 파싱 실패");
    }

    @Test
    void 결측_이상_행은_스킵하고_정상행만_담는다() {  // NOSONAR
        KrxApiClient client = clientWith(props("KEY", 1000));
        String noCode = "{\"srtnCd\":\"\",\"mrktCtg\":\"KOSPI\",\"clpr\":\"1000\"}";
        String unknownMarket = "{\"srtnCd\":\"111111\",\"mrktCtg\":\"NYSE\",\"clpr\":\"1000\"}";
        String noClose = "{\"srtnCd\":\"222222\",\"mrktCtg\":\"KOSPI\",\"clpr\":\"-\"}";
        // 정상행이지만 부가 숫자필드(mkp)가 파싱 불가 → 그 필드만 null, 행은 유지(파싱 catch 커버)
        String badOptional = "{\"srtnCd\":\"005930\",\"mrktCtg\":\"KOSPI\",\"clpr\":\"78000\",\"mkp\":\"abc\"}";
        String items = "[" + noCode + "," + unknownMarket + "," + noClose + "," + badOptional + "]";
        server.expect(requestTo(containsString("getStockPriceInfo")))
                .andRespond(withSuccess(okBody(4, items), APPLICATION_JSON));

        List<StockPrice> prices = client.fetchQuotes(BASE);

        assertThat(prices).hasSize(1);
        StockPrice only = prices.get(0);
        assertThat(only.stockCode()).isEqualTo("005930");
        assertThat(only.openPrice()).isNull();   // "abc" → null
        assertThat(only.isin()).isNull();         // isinCd 결측 → blankToNull
    }

    // 시장구분 코드 번역은 도메인이 아니라 이 어댑터의 책임이다 (Market.fromCode 에서 내려왔다).

    @Test
    void 정상_시장코드는_매핑된다() {
        assertThat(KrxApiClient.toMarket("KOSPI")).isEqualTo(Market.KOSPI);
        assertThat(KrxApiClient.toMarket(" kosdaq ")).isEqualTo(Market.KOSDAQ);
        assertThat(KrxApiClient.toMarket("KONEX")).isEqualTo(Market.KONEX);
    }

    @Test
    void 알수없는_값이나_공백은_null() {
        assertThat(KrxApiClient.toMarket(null)).isNull();
        assertThat(KrxApiClient.toMarket("")).isNull();
        assertThat(KrxApiClient.toMarket("NASDAQ")).isNull();
    }
}
