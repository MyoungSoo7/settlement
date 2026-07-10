package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.domain.AnnualStatement;
import github.lms.lemuel.investment.domain.CompanyFinancials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * FinancialStatementsApiClient — financial 공개 API 계약 파싱을 MockRestServiceServer 로 검증.
 * (정상 매핑·CFS 우선 dedupe·결측/비숫자 null·404 empty·비404 예외·JSON 파싱실패 예외)
 */
class FinancialStatementsApiClientTest {

    private static final String BASE = "http://financial.test";
    private static final String COMPANY_URI = BASE + "/api/financial/companies/005930";
    private static final String STATEMENTS_URI = BASE + "/api/financial/companies/005930/statements";

    private MockRestServiceServer server;
    private FinancialStatementsApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FinancialStatementsApiClient(builder, new ObjectMapper(), BASE);
    }

    @Test
    @DisplayName("회사+재무제표 정상 응답을 도메인으로 매핑한다")
    void mapsCompanyAndStatements() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess("""
                {"stockCode":"005930","name":"삼성전자","market":"KOSPI"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(STATEMENTS_URI)).andRespond(withSuccess("""
                [{"fiscalYear":2024,"fsDivision":"CFS","revenue":"300000","operatingProfit":"30000",
                  "netIncome":"25000","totalAssets":"500000","totalLiabilities":"200000",
                  "totalEquity":"300000","operatingMargin":"10.0","netMargin":"8.3",
                  "debtRatio":"66.6","equityRatio":"60.0","roa":"5.0"}]
                """, MediaType.APPLICATION_JSON));

        Optional<CompanyFinancials> result = client.load("005930");

        assertThat(result).isPresent();
        CompanyFinancials cf = result.get();
        assertThat(cf.stockCode()).isEqualTo("005930");
        assertThat(cf.companyName()).isEqualTo("삼성전자");
        assertThat(cf.market()).isEqualTo("KOSPI");
        assertThat(cf.statements()).hasSize(1);
        AnnualStatement s = cf.statements().get(0);
        assertThat(s.fiscalYear()).isEqualTo(2024);
        assertThat(s.revenue()).isEqualByComparingTo("300000");
        assertThat(s.roa()).isEqualByComparingTo("5.0");
        server.verify();
    }

    @Test
    @DisplayName("같은 연도 CFS·OFS 가 오면 CFS 를 채택하고 OFS 는 무시한다")
    void prefersCfsOverOfsForSameYear() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess(
                "{\"stockCode\":\"005930\"}", MediaType.APPLICATION_JSON));
        // CFS 먼저 채택 후 같은 연도 OFS 는 무시된다
        server.expect(requestTo(STATEMENTS_URI)).andRespond(withSuccess("""
                [{"fiscalYear":2024,"fsDivision":"CFS","revenue":"300000"},
                 {"fiscalYear":2024,"fsDivision":"OFS","revenue":"111111"}]
                """, MediaType.APPLICATION_JSON));

        CompanyFinancials cf = client.load("005930").orElseThrow();

        assertThat(cf.statements()).hasSize(1);
        assertThat(cf.statements().get(0).revenue()).isEqualByComparingTo("300000");
        // name/market 결측 → text() fallback null
        assertThat(cf.companyName()).isNull();
        assertThat(cf.market()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("결측(null/공백) 및 비숫자 금액은 null 로 담는다")
    void nullAndNonNumericBecomeNull() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess(
                "{\"stockCode\":\"005930\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STATEMENTS_URI)).andRespond(withSuccess("""
                [{"fiscalYear":2023,"fsDivision":"CFS","revenue":null,"operatingProfit":"",
                  "netIncome":"not-a-number","totalAssets":"400000"}]
                """, MediaType.APPLICATION_JSON));

        AnnualStatement s = client.load("005930").orElseThrow().statements().get(0);

        assertThat(s.revenue()).isNull();
        assertThat(s.operatingProfit()).isNull();
        assertThat(s.netIncome()).isNull();          // NumberFormatException → null
        assertThat(s.totalAssets()).isEqualByComparingTo("400000");
        server.verify();
    }

    @Test
    @DisplayName("회사 404 는 Optional.empty")
    void company404ReturnsEmpty() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.load("005930")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("재무제표가 비면 Optional.empty")
    void emptyStatementsReturnsEmpty() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess(
                "{\"stockCode\":\"005930\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STATEMENTS_URI)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.load("005930")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("재무제표 응답이 배열이 아니면 빈 목록으로 처리(empty)")
    void nonArrayStatementsReturnsEmpty() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess(
                "{\"stockCode\":\"005930\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STATEMENTS_URI)).andRespond(withSuccess(
                "{\"unexpected\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.load("005930")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("404 외 HTTP 오류는 IllegalStateException")
    void non404ErrorThrows() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.load("005930"))
                .withMessageContaining("financial API 오류");
    }

    @Test
    @DisplayName("JSON 파싱 실패는 IllegalStateException")
    void invalidJsonThrows() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess(
                "not-json{{", MediaType.APPLICATION_JSON));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.load("005930"))
                .withMessageContaining("파싱 실패");
    }
}
