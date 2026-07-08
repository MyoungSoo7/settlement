package github.lms.lemuel.company.adapter.out.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmSentimentAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 키가 설정된 분석기 + Anthropic HTTP 응답을 가로채는 MockRestServiceServer 쌍(실네트워크 없음). */
    private record Fixture(LlmSentimentAnalyzer analyzer, MockRestServiceServer server) {
    }

    private Fixture configuredWith(String responseBody, boolean success) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeSentimentProperties props = new ClaudeSentimentProperties("test-key", null, null, null, 0);
        LlmSentimentAnalyzer analyzer = new LlmSentimentAnalyzer(props, builder, objectMapper);
        server.expect(requestTo(endsWith("/v1/messages")))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(success
                        ? withSuccess(responseBody, MediaType.APPLICATION_JSON)
                        : withServerError());
        return new Fixture(analyzer, server);
    }

    @Test
    @DisplayName("API 키 미설정이면 호출 없이 키워드 분석기로 폴백한다")
    void fallsBackWhenNotConfigured() {
        ClaudeSentimentProperties props = new ClaudeSentimentProperties("", null, null, null, 0);
        LlmSentimentAnalyzer analyzer = new LlmSentimentAnalyzer(props, RestClient.builder(), objectMapper);

        // 키워드 분석기가 '분식'을 FINANCIAL 부정으로 잡는다 — LLM 호출 없이 동일 결과
        ArticleSentiment result = analyzer.analyze("삼성전자 분식회계 의혹", "검찰 수사");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.FINANCIAL, result.category());
    }

    @Test
    @DisplayName("정상 응답의 라벨을 파싱해 도메인 감성으로 매핑한다")
    void mapsLabelFromSuccessfulResponse() {
        Fixture fx = configuredWith("{\"content\":[{\"type\":\"text\",\"text\":\"POSITIVE\"}]}", true);

        // 키워드로는 신호 없는 제목이라도 LLM 라벨(POSITIVE)이 결과를 결정한다
        ArticleSentiment result = fx.analyzer().analyze("어떤 기업 소식", "특이사항 없음");

        assertEquals(Sentiment.POSITIVE, result.sentiment());
        fx.server().verify();
    }

    @Test
    @DisplayName("NEGATIVE_PRODUCT 라벨은 PRODUCT 부정으로 매핑한다")
    void mapsProductNegative() {
        Fixture fx = configuredWith("{\"content\":[{\"type\":\"text\",\"text\":\"NEGATIVE_PRODUCT\"}]}", true);

        ArticleSentiment result = fx.analyzer().analyze("제목", "요약");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.PRODUCT, result.category());
        fx.server().verify();
    }

    @Test
    @DisplayName("content 가 비면(거절 등) 파싱 실패 → 키워드 폴백")
    void fallsBackOnEmptyContent() {
        Fixture fx = configuredWith("{\"content\":[],\"stop_reason\":\"end_turn\"}", true);

        // '리콜' → PRODUCT 부정으로 폴백됨을 확인
        ArticleSentiment result = fx.analyzer().analyze("대규모 리콜 사태", "");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.PRODUCT, result.category());
        fx.server().verify();
    }

    @Test
    @DisplayName("HTTP 5xx 오류면 키워드 폴백")
    void fallsBackOnHttpError() {
        Fixture fx = configuredWith(null, false);

        ArticleSentiment result = fx.analyzer().analyze("사상최대 실적", "");

        assertEquals(Sentiment.POSITIVE, result.sentiment());
        fx.server().verify();
    }

    @Test
    @DisplayName("응답이 잘못된 JSON 이면 파싱 실패 → 키워드 폴백")
    void fallsBackOnMalformedJson() {
        Fixture fx = configuredWith("<<not-json", true);

        ArticleSentiment result = fx.analyzer().analyze("특이사항 없음", "");

        assertEquals(Sentiment.NEUTRAL, result.sentiment());
        fx.server().verify();
    }
}
