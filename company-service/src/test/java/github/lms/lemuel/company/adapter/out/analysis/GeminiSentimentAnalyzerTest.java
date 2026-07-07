package github.lms.lemuel.company.adapter.out.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeminiSentimentAnalyzerTest {

    @Test
    @DisplayName("API 키 미설정이면 호출 없이 키워드 분석기로 폴백한다")
    void fallsBackWhenNotConfigured() {
        GeminiSentimentProperties props = new GeminiSentimentProperties("", null, null, 0);
        GeminiSentimentAnalyzer analyzer = new GeminiSentimentAnalyzer(props, RestClient.builder(), new ObjectMapper());

        // 키워드 분석기가 '리콜'을 PRODUCT 부정으로 잡는다 — Gemini 호출 없이 동일 결과
        ArticleSentiment result = analyzer.analyze("현대차 대규모 리콜", "브레이크 결함");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.PRODUCT, result.category());
    }
}
