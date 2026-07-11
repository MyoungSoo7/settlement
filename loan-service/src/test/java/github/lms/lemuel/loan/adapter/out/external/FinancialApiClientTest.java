package github.lms.lemuel.loan.adapter.out.external;

import github.lms.lemuel.loan.domain.CorporateFinancials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * financial-statements-service 공개 API HTTP 클라이언트({@link FinancialApiClient}) 단위 테스트.
 * MockRestServiceServer 로 정상(회사+재무제표)/회사없음/재무없음/API실패 4경로를 검증한다.
 */
class FinancialApiClientTest {

    private static final String BASE = "http://localhost:8086";

    private record Fixture(FinancialApiClient client, MockRestServiceServer server) {
    }

    private Fixture newClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FinancialApiClient client = new FinancialApiClient(builder, BASE);
        return new Fixture(client, server);
    }

    @Test
    @DisplayName("회사 + 재무제표 정상 → 최신 회계연도 재무를 매핑한다")
    void loadLatest_mapsNewestFiscalYear() {
        Fixture f = newClient();
        f.server().expect(requestTo(BASE + "/api/financial/companies/005930"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"stockCode":"005930","corpCode":"00126380","name":"삼성전자","market":"KOSPI"}
                        """, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(BASE + "/api/financial/companies/005930/statements"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [
                          {"fiscalYear":2023,"totalEquity":100,"netIncome":10,"operatingMargin":12.0,"debtRatio":45.0,"roa":7.0},
                          {"fiscalYear":2025,"totalEquity":200,"netIncome":30,"operatingMargin":15.5,"debtRatio":40.2,"roa":8.1}
                        ]
                        """, MediaType.APPLICATION_JSON));

        Optional<CorporateFinancials> result = f.client().loadLatest("005930");

        f.server().verify();
        assertThat(result).isPresent();
        CorporateFinancials cf = result.get();
        assertThat(cf.stockCode()).isEqualTo("005930");
        assertThat(cf.corpName()).isEqualTo("삼성전자");
        assertThat(cf.market()).isEqualTo("KOSPI");
        assertThat(cf.fiscalYear()).isEqualTo(2025);
        assertThat(cf.debtRatio()).isEqualByComparingTo("40.2");
        assertThat(cf.operatingMargin()).isEqualByComparingTo("15.5");
        assertThat(cf.roa()).isEqualByComparingTo("8.1");
        assertThat(cf.totalEquity()).isEqualByComparingTo("200");
        assertThat(cf.netIncome()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("회사 응답이 null(빈) 이면 Optional.empty")
    void loadLatest_companyNull_empty() {
        Fixture f = newClient();
        f.server().expect(requestTo(BASE + "/api/financial/companies/000000"))
                .andExpect(method(GET))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        Optional<CorporateFinancials> result = f.client().loadLatest("000000");

        f.server().verify();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("재무제표가 null 이면 Optional.empty")
    void loadLatest_statementsNull_empty() {
        Fixture f = newClient();
        f.server().expect(requestTo(BASE + "/api/financial/companies/005930"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"stockCode":"005930","corpCode":"00126380","name":"삼성전자","market":"KOSPI"}
                        """, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(BASE + "/api/financial/companies/005930/statements"))
                .andExpect(method(GET))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        Optional<CorporateFinancials> result = f.client().loadLatest("005930");

        f.server().verify();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("재무제표가 빈 배열이면 Optional.empty (max 없음)")
    void loadLatest_statementsEmptyArray_empty() {
        Fixture f = newClient();
        f.server().expect(requestTo(BASE + "/api/financial/companies/005930"))
                .andRespond(withSuccess("""
                        {"stockCode":"005930","corpCode":"00126380","name":"삼성전자","market":"KOSPI"}
                        """, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(BASE + "/api/financial/companies/005930/statements"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        Optional<CorporateFinancials> result = f.client().loadLatest("005930");

        f.server().verify();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("API 실패(404)는 RestClientException 을 흡수해 Optional.empty")
    void loadLatest_apiError_empty() {
        Fixture f = newClient();
        f.server().expect(requestTo(BASE + "/api/financial/companies/999999"))
                .andExpect(method(GET))
                .andRespond(withStatus(NOT_FOUND));

        Optional<CorporateFinancials> result = f.client().loadLatest("999999");

        f.server().verify();
        assertThat(result).isEmpty();
    }
}
