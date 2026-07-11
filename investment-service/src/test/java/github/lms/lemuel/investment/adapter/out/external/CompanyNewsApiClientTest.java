package github.lms.lemuel.investment.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.investment.domain.NewsArticleSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CompanyNewsApiClient — company 공개 API 계약 파싱을 MockRestServiceServer 로 검증
 * (기업 존재 확인 → 기사 페이지 파싱 · 404 empty · 비404 예외 · publishedAt 결측 허용).
 */
class CompanyNewsApiClientTest {

    private static final String BASE = "http://company.test";
    private static final String COMPANY_URI = BASE + "/api/company/companies/005930";
    private static final String ARTICLES_URI = BASE + "/api/company/companies/005930/articles?page=0&size=50";

    private MockRestServiceServer server;
    private CompanyNewsApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CompanyNewsApiClient(builder, new ObjectMapper(), BASE);
    }

    @Test
    @DisplayName("기업 존재 + 기사 페이지를 도메인으로 매핑한다 (publishedAt 결측은 null)")
    void mapsArticles() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withSuccess("""
                {"stockCode":"005930","name":"삼성전자"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ARTICLES_URI)).andRespond(withSuccess("""
                {"content":[
                  {"title":"유상증자 결정","summary":"요약1","url":"https://n/1",
                   "publishedAt":"2026-07-10T01:02:03Z"},
                  {"title":"신제품 출시","summary":"요약2","url":"https://n/2","publishedAt":null}
                ],"page":0,"size":50,"totalElements":2,"totalPages":1}
                """, MediaType.APPLICATION_JSON));

        Optional<List<NewsArticleSummary>> result = client.loadRecentArticles("005930");

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).title()).isEqualTo("유상증자 결정");
        assertThat(result.get().get(0).publishedAt()).isEqualTo(Instant.parse("2026-07-10T01:02:03Z"));
        assertThat(result.get().get(1).publishedAt()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("기업 404 이면 empty Optional — 기사 API 는 호출하지 않는다")
    void emptyWhenCompanyNotFound() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.loadRecentArticles("005930")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("비404 오류는 IllegalStateException — 서비스가 축 강등으로 처리한다")
    void throwsOnServerError() {
        server.expect(requestTo(COMPANY_URI)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatIllegalStateException().isThrownBy(() -> client.loadRecentArticles("005930"))
                .withMessageContaining("company API 오류");
    }
}
