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

class GeminiSentimentAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 키가 설정된 분석기 + Gemini HTTP 응답을 가로채는 MockRestServiceServer 쌍을 만든다(실네트워크 없음). */
    private record Fixture(GeminiSentimentAnalyzer analyzer, MockRestServiceServer server) {
    }

    private Fixture configuredWith(String responseBody, boolean success) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiSentimentProperties props = new GeminiSentimentProperties("test-key", null, null, 0, 0, 0);
        GeminiSentimentAnalyzer analyzer = new GeminiSentimentAnalyzer(props, builder, objectMapper);
        server.expect(requestTo(endsWith("generateContent")))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(success
                        ? withSuccess(responseBody, MediaType.APPLICATION_JSON)
                        : withServerError());
        return new Fixture(analyzer, server);
    }

    @Test
    @DisplayName("API 키 미설정이면 호출 없이 키워드 분석기로 폴백한다")
    void fallsBackWhenNotConfigured() {
        GeminiSentimentProperties props = new GeminiSentimentProperties("", null, null, 0, 0, 0);
        GeminiSentimentAnalyzer analyzer = new GeminiSentimentAnalyzer(props, RestClient.builder(), objectMapper);

        // 키워드 분석기가 '리콜'을 PRODUCT 부정으로 잡는다 — Gemini 호출 없이 동일 결과
        ArticleSentiment result = analyzer.analyze("현대차 대규모 리콜", "브레이크 결함");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.PRODUCT, result.category());
    }

    @Test
    @DisplayName("정상 응답의 라벨을 파싱해 도메인 감성으로 매핑한다")
    void mapsLabelFromSuccessfulResponse() {
        Fixture fx = configuredWith(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"NEGATIVE_LEGAL\"}]}}]}", true);

        // 키워드로는 아무 신호 없는 제목이라도 LLM 라벨(NEGATIVE_LEGAL)이 결과를 결정한다
        ArticleSentiment result = fx.analyzer().analyze("어떤 기업 소식", "특이사항 없음");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.LEGAL, result.category());
        fx.server().verify();
    }

    @Test
    @DisplayName("알 수 없는 라벨은 보수적으로 NEUTRAL 로 매핑한다")
    void mapsUnknownLabelToNeutral() {
        Fixture fx = configuredWith(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"WHATEVER\"}]}}]}", true);

        ArticleSentiment result = fx.analyzer().analyze("제목", "요약");

        assertEquals(Sentiment.NEUTRAL, result.sentiment());
        fx.server().verify();
    }

    @Test
    @DisplayName("candidates 가 비면 파싱 실패 → 키워드 폴백")
    void fallsBackOnEmptyCandidates() {
        Fixture fx = configuredWith("{\"candidates\":[],\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", true);

        // '분식' → FINANCIAL 부정으로 폴백됨을 확인
        ArticleSentiment result = fx.analyzer().analyze("분식회계 의혹", "");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.FINANCIAL, result.category());
        fx.server().verify();
    }

    @Test
    @DisplayName("parts 가 비면 파싱 실패 → 키워드 폴백")
    void fallsBackOnEmptyParts() {
        Fixture fx = configuredWith(
                "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}", true);

        ArticleSentiment result = fx.analyzer().analyze("파업 장기화", "");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.LABOR, result.category());
        fx.server().verify();
    }

    @Test
    @DisplayName("HTTP 5xx 오류면 키워드 폴백")
    void fallsBackOnHttpError() {
        Fixture fx = configuredWith(null, false);

        ArticleSentiment result = fx.analyzer().analyze("신제품 흥행", "");

        assertEquals(Sentiment.POSITIVE, result.sentiment());
        fx.server().verify();
    }

    @Test
    @DisplayName("응답이 잘못된 JSON 이면 파싱 실패 → 키워드 폴백")
    void fallsBackOnMalformedJson() {
        Fixture fx = configuredWith("not-json{", true);

        ArticleSentiment result = fx.analyzer().analyze("특이사항 없음", "");

        assertEquals(Sentiment.NEUTRAL, result.sentiment());
        fx.server().verify();
    }
}
