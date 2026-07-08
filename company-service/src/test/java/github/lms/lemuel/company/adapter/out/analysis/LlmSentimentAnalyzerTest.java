package github.lms.lemuel.company.adapter.out.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmSentimentAnalyzerTest {

    @Test
    @DisplayName("API 키 미설정이면 호출 없이 키워드 분석기로 폴백한다")
    void fallsBackWhenNotConfigured() {
        ClaudeSentimentProperties props = new ClaudeSentimentProperties("", null, null, null, 0);
        LlmSentimentAnalyzer analyzer = new LlmSentimentAnalyzer(props, RestClient.builder(), new ObjectMapper());

        // 키워드 분석기가 '분식'을 FINANCIAL 부정으로 잡는다 — LLM 호출 없이 동일 결과
        ArticleSentiment result = analyzer.analyze("삼성전자 분식회계 의혹", "검찰 수사");

        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.FINANCIAL, result.category());
    }
}
