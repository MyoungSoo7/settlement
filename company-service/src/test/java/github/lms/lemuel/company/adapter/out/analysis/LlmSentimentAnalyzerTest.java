package github.lms.lemuel.company.adapter.out.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmSentimentAnalyzerTest {

    private static ClaudeSentimentProperties props(String apiKey) {
        return new ClaudeSentimentProperties(apiKey, null, null, null, 0);
    }

    @Test
    @DisplayName("라벨 → 도메인 매핑 (부정은 카테고리, 알 수 없는 라벨은 NEUTRAL)")
    void labelMapping() {
        assertEquals(Sentiment.POSITIVE, LlmSentimentAnalyzer.toSentiment("POSITIVE").sentiment());
        assertEquals(IssueCategory.FINANCIAL,
                LlmSentimentAnalyzer.toSentiment("NEGATIVE_FINANCIAL").category());
        assertEquals(IssueCategory.LEGAL,
                LlmSentimentAnalyzer.toSentiment(" negative_legal \n").category());   // 공백/소문자 관대
        assertEquals(Sentiment.NEGATIVE, LlmSentimentAnalyzer.toSentiment("NEGATIVE_OTHER").sentiment());
        assertNull(LlmSentimentAnalyzer.toSentiment("NEGATIVE_OTHER").category());
        assertEquals(Sentiment.NEUTRAL, LlmSentimentAnalyzer.toSentiment("횡설수설").sentiment());
        assertEquals(Sentiment.NEUTRAL, LlmSentimentAnalyzer.toSentiment(null).sentiment());
    }

    @Test
    @DisplayName("API 키 미설정이면 호출 없이 키워드 분석기로 폴백한다")
    void fallsBackWhenNotConfigured() {
        LlmSentimentAnalyzer analyzer = new LlmSentimentAnalyzer(
                props(""), RestClient.builder(), new ObjectMapper());

        // 키워드 분석기가 '분식'을 FINANCIAL 부정으로 잡는다 — LLM 호출 없이 동일 결과
        ArticleSentiment result = analyzer.analyze("삼성전자 분식회계 의혹", "검찰 수사");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.FINANCIAL, result.category());
    }
}
