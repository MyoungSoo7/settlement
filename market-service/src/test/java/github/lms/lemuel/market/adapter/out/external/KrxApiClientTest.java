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
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new KrxApiClient(props, builder, new ObjectMapper());
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
}
