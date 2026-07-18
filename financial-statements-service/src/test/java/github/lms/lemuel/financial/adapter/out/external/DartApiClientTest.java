package github.lms.lemuel.financial.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.financial.application.port.out.DartClientPort.AnnualSummary;
import github.lms.lemuel.financial.application.port.out.DartClientPort.CompanyProfile;
import github.lms.lemuel.financial.application.port.out.DartClientPort.ListedCompany;
import github.lms.lemuel.financial.domain.FsDivision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DartApiClient — OpenDART 계약 파싱을 MockRestServiceServer 로 검증.
 * (corpCode.xml zip 파싱, company.json 개황, fnlttSinglAcnt.json 요약계정 정규화·연결/별도 선택,
 *  status 코드 분기, 금액 콤마/대시 파싱)
 */
class DartApiClientTest {

    private static final String BASE = "https://dart.test/api";

    private MockRestServiceServer server;
    private DartApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DartApiClient(new DartProperties("TESTKEY", BASE), builder, new ObjectMapper());
    }

    private byte[] corpCodeZip(String entryName, String xml) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("isConfigured — DartProperties.configured 위임")
    void isConfigured() {
        assertThat(client.isConfigured()).isTrue();

        DartApiClient noKey = new DartApiClient(
                new DartProperties("", BASE), RestClient.builder(), new ObjectMapper());
        assertThat(noKey.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("corpCode.xml — 종목코드 보유한 상장사만 추출(공백 종목코드 제외)")
    void fetchListedCompanies() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <result>
                  <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name><stock_code>005930</stock_code></list>
                  <list><corp_code>00999999</corp_code><corp_name>비상장</corp_name><stock_code>   </stock_code></list>
                  <list><corp_code>00888888</corp_code><corp_name>노종목</corp_name></list>
                </result>
                """;
        server.expect(requestTo(startsWith(BASE + "/corpCode.xml")))
                .andRespond(withSuccess(corpCodeZip("CORPCODE.xml", xml), MediaType.APPLICATION_OCTET_STREAM));

        List<ListedCompany> companies = client.fetchListedCompanies();

        assertThat(companies).hasSize(1);
        assertThat(companies.get(0)).isEqualTo(new ListedCompany("00126380", "005930", "삼성전자"));
        server.verify();
    }

    @Test
    @DisplayName("corpCode.xml — 빈 응답이면 IllegalStateException")
    void fetchListedCompaniesEmpty() {
        server.expect(requestTo(startsWith(BASE + "/corpCode.xml")))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_OCTET_STREAM));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.fetchListedCompanies())
                .withMessageContaining("비어 있습니다");
    }

    @Test
    @DisplayName("corpCode.xml — zip 안에 XML 엔트리 없으면 파싱 실패")
    void fetchListedCompaniesNoXmlEntry() throws Exception {
        server.expect(requestTo(startsWith(BASE + "/corpCode.xml")))
                .andRespond(withSuccess(corpCodeZip("readme.txt", "hello"),
                        MediaType.APPLICATION_OCTET_STREAM));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.fetchListedCompanies())
                .withMessageContaining("파싱 실패");
    }

    @Test
    @DisplayName("company.json — status 000 정상 개황")
    void fetchProfileOk() {
        server.expect(requestTo(startsWith(BASE + "/company.json")))
                .andRespond(withSuccess(
                        "{\"status\":\"000\",\"corp_cls\":\"Y\",\"corp_name\":\"삼성전자\"}",
                        MediaType.APPLICATION_JSON));

        Optional<CompanyProfile> profile = client.fetchProfile("00126380");

        assertThat(profile).contains(new CompanyProfile("00126380", "Y", "삼성전자"));
        assertThat(profile.get().marketOrNull()).isEqualTo("KOSPI");
        server.verify();
    }

    @Test
    @DisplayName("company.json — status 013(데이터 없음)은 empty")
    void fetchProfileNoData() {
        server.expect(requestTo(startsWith(BASE + "/company.json")))
                .andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이터가 없습니다.\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchProfile("00000000")).isEmpty();
    }

    @Test
    @DisplayName("company.json — 빈 본문이면 status 불일치로 IllegalStateException(오류 status)")
    void fetchProfileEmptyBodyThrows() {
        server.expect(requestTo(startsWith(BASE + "/company.json")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.fetchProfile("00126380"))
                .withMessageContaining("DART 오류");
    }

    @Test
    @DisplayName("fnlttSinglAcnt.json — 연결(CFS) 우선, 계정 정규화·콤마 금액 파싱, 미매칭/대시 skip")
    void fetchAnnualSummaryCfs() {
        server.expect(requestTo(startsWith(BASE + "/fnlttSinglAcnt.json")))
                .andRespond(withSuccess("""
                        {"status":"000","list":[
                          {"fs_div":"CFS","account_nm":"매출액","thstrm_amount":"1,000","currency":"KRW"},
                          {"fs_div":"CFS","account_nm":"영업이익","thstrm_amount":"150"},
                          {"fs_div":"CFS","account_nm":"당기순이익","thstrm_amount":"100"},
                          {"fs_div":"CFS","account_nm":"자산총계","thstrm_amount":"2000"},
                          {"fs_div":"CFS","account_nm":"부채총계","thstrm_amount":"800"},
                          {"fs_div":"CFS","account_nm":"자본총계","thstrm_amount":"1200"},
                          {"fs_div":"CFS","account_nm":"기타항목","thstrm_amount":"9"},
                          {"fs_div":"CFS","account_nm":"매출액","thstrm_amount":"-"},
                          {"fs_div":"CFS","account_nm":"수익(매출액)","thstrm_amount":"abc"},
                          {"fs_div":"OFS","account_nm":"영업수익","thstrm_amount":"500"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Optional<AnnualSummary> summary = client.fetchAnnualSummary("00126380", 2024);

        assertThat(summary).isPresent();
        AnnualSummary s = summary.get();
        assertThat(s.fsDivision()).isEqualTo(FsDivision.CFS);
        assertThat(s.currency()).isEqualTo("KRW");
        assertThat(s.revenue()).isEqualByComparingTo("1000");
        assertThat(s.operatingProfit()).isEqualByComparingTo("150");
        assertThat(s.netIncome()).isEqualByComparingTo("100");
        assertThat(s.totalAssets()).isEqualByComparingTo("2000");
        assertThat(s.totalLiabilities()).isEqualByComparingTo("800");
        assertThat(s.totalEquity()).isEqualByComparingTo("1200");
        server.verify();
    }

    @Test
    @DisplayName("fnlttSinglAcnt.json — 연결 계정이 없으면 별도(OFS) 기준")
    void fetchAnnualSummaryOfs() {
        server.expect(requestTo(startsWith(BASE + "/fnlttSinglAcnt.json")))
                .andRespond(withSuccess("""
                        {"status":"000","list":[
                          {"fs_div":"OFS","account_nm":"매출액","thstrm_amount":"700","currency":"USD"},
                          {"fs_div":"OFS","account_nm":"자본총계","thstrm_amount":"300"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        AnnualSummary s = client.fetchAnnualSummary("00126380", 2024).orElseThrow();

        assertThat(s.fsDivision()).isEqualTo(FsDivision.OFS);
        assertThat(s.currency()).isEqualTo("USD");
        assertThat(s.revenue()).isEqualByComparingTo("700");
        assertThat(s.totalEquity()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("fnlttSinglAcnt.json — status 013 은 empty")
    void fetchAnnualSummaryNoData() {
        server.expect(requestTo(startsWith(BASE + "/fnlttSinglAcnt.json")))
                .andRespond(withSuccess("{\"status\":\"013\"}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchAnnualSummary("00126380", 2024)).isEmpty();
    }

    @Test
    @DisplayName("fnlttSinglAcnt.json — list 가 비면 empty")
    void fetchAnnualSummaryEmptyList() {
        server.expect(requestTo(startsWith(BASE + "/fnlttSinglAcnt.json")))
                .andRespond(withSuccess("{\"status\":\"000\",\"list\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchAnnualSummary("00126380", 2024)).isEmpty();
    }

    @Test
    @DisplayName("fnlttSinglAcnt.json — 매칭 계정이 하나도 없으면 empty")
    void fetchAnnualSummaryNoMatchedAccounts() {
        server.expect(requestTo(startsWith(BASE + "/fnlttSinglAcnt.json")))
                .andRespond(withSuccess("""
                        {"status":"000","list":[
                          {"fs_div":"CFS","account_nm":"기타","thstrm_amount":"1"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchAnnualSummary("00126380", 2024)).isEmpty();
    }

    @Test
    @DisplayName("fnlttSinglAcnt.json — status 000/013 외 코드는 예외")
    void fetchAnnualSummaryErrorStatus() {
        server.expect(requestTo(startsWith(BASE + "/fnlttSinglAcnt.json")))
                .andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한 초과\"}",
                        MediaType.APPLICATION_JSON));

        assertThatIllegalStateException()
                .isThrownBy(() -> client.fetchAnnualSummary("00126380", 2024))
                .withMessageContaining("020");
    }
}
