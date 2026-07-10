package github.lms.lemuel.company.adapter.out.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.application.port.out.NewsClientPort.NewsItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NaverNewsApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Fixture(NaverNewsApiClient client, MockRestServiceServer server) {
    }

    private Fixture withResponse(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverNewsProperties props = new NaverNewsProperties("cid", "csecret", null, 20);
        NaverNewsApiClient client = new NaverNewsApiClient(props, builder, objectMapper);
        server.expect(requestTo(startsWith("https://openapi.naver.com/v1/search/news.json")))
                .andExpect(header("X-Naver-Client-Id", "cid"))
                .andExpect(header("X-Naver-Client-Secret", "csecret"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return new Fixture(client, server);
    }

    @Test
    @DisplayName("wire 포맷을 정리해 NewsItem 으로 매핑한다 — 태그·엔티티 제거, host 를 publisher 로, pubDate 파싱")
    void parsesAndCleansItems() {
        String body = """
                {"items":[
                  {"title":"<b>삼성전자</b> 신제품 &quot;흥행&quot; &amp; 호평",
                   "description":"요약 &lt;태그&gt; 내용",
                   "originallink":"https://news.example.com/a/1",
                   "link":"https://n.news.naver.com/x",
                   "pubDate":"Tue, 07 Jul 2026 09:00:00 +0900"},
                  {"title":"제목만",
                   "description":"",
                   "originallink":"",
                   "link":"https://link.example.org/b/2",
                   "pubDate":""}
                ]}
                """;
        Fixture fx = withResponse(body);

        List<NewsItem> items = fx.client().fetchNews("삼성전자");

        assertEquals(2, items.size());
        NewsItem first = items.get(0);
        assertEquals("삼성전자 신제품 \"흥행\" & 호평", first.title());
        assertEquals("요약 <태그> 내용", first.summary());
        assertEquals("news.example.com", first.publisher());
        assertEquals("https://news.example.com/a/1", first.url());
        assertEquals(Instant.parse("2026-07-07T00:00:00Z"), first.publishedAt());

        NewsItem second = items.get(1);
        // originallink 가 비면 link 를 쓴다
        assertEquals("https://link.example.org/b/2", second.url());
        assertEquals("link.example.org", second.publisher());
        // pubDate 가 비면 null
        assertNull(second.publishedAt());
        // 빈 description 은 wire 단계에서 빈 문자열(도메인 단계에서 null 로 정규화됨)
        assertEquals("", second.summary());
        fx.server().verify();
    }

    @Test
    @DisplayName("빈 items 응답은 빈 목록")
    void emptyItems() {
        Fixture fx = withResponse("{\"items\":[]}");

        assertTrue(fx.client().fetchNews("없는기업").isEmpty());
        fx.server().verify();
    }

    @Test
    @DisplayName("errorCode 가 담긴 응답은 IllegalStateException")
    void errorCodeResponse() {
        Fixture fx = withResponse("{\"errorCode\":\"024\",\"errorMessage\":\"Authentication failed\"}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> fx.client().fetchNews("삼성전자"));
        assertTrue(ex.getMessage().contains("024"));
    }

    @Test
    @DisplayName("깨진 pubDate 는 null 로 처리하고 나머지 필드는 유지한다")
    void malformedPubDate() {
        String body = """
                {"items":[
                  {"title":"제목","description":"요약","originallink":"https://a.example.com/1",
                   "link":"","pubDate":"not-a-date"}
                ]}
                """;
        Fixture fx = withResponse(body);

        List<NewsItem> items = fx.client().fetchNews("x");

        assertEquals(1, items.size());
        assertNull(items.get(0).publishedAt());
        assertEquals("제목", items.get(0).title());
    }

    @Test
    @DisplayName("isConfigured 는 properties.configured 를 위임한다")
    void isConfigured() {
        NaverNewsProperties configured = new NaverNewsProperties("cid", "csecret", null, 20);
        NaverNewsProperties missing = new NaverNewsProperties("", "", null, 20);
        assertTrue(new NaverNewsApiClient(configured, RestClient.builder(), objectMapper).isConfigured());
        assertFalse(new NaverNewsApiClient(missing, RestClient.builder(), objectMapper).isConfigured());
    }

    @Test
    @DisplayName("cleanText 는 태그와 HTML 엔티티를 제거하고 트림한다")
    void cleanTextEdgeCases() {
        assertEquals("a<b>c", NaverNewsApiClient.cleanText("  a&lt;b&gt;c  "));
        assertEquals("'\"&", NaverNewsApiClient.cleanText("&#39;&quot;&amp;"));
        assertEquals("plain", NaverNewsApiClient.cleanText("<span class='x'>plain</span>"));
        assertEquals("apos'", NaverNewsApiClient.cleanText("apos&apos;"));
    }

    @Test
    @DisplayName("hostOf 는 유효 URL 의 호스트를, 잘못된/호스트없는 URL 은 null 을 돌려준다")
    void hostOfEdgeCases() {
        assertEquals("example.com", NaverNewsApiClient.hostOf("https://example.com/path"));
        assertNull(NaverNewsApiClient.hostOf("not a url"));
        assertNull(NaverNewsApiClient.hostOf("mailto:someone"));
    }
}
