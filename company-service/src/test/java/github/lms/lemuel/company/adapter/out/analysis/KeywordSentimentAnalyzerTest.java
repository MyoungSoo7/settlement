package github.lms.lemuel.company.adapter.out.analysis;

import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeywordSentimentAnalyzerTest {

    private final KeywordSentimentAnalyzer analyzer = new KeywordSentimentAnalyzer();

    @Test
    @DisplayName("부정 키워드는 카테고리와 함께 NEGATIVE 로 분류")
    void classifiesNegativeWithCategory() {
        ArticleSentiment result = analyzer.analyze("삼성전자 분식회계 의혹", "검찰 수사 착수");
        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.FINANCIAL, result.category());
    }

    @Test
    @DisplayName("띄어쓰기가 달라도 키워드를 잡는다 (공백 제거 후 매칭)")
    void matchesAcrossWhitespace() {
        ArticleSentiment result = analyzer.analyze("대규모 리 콜 사태", null);
        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.PRODUCT, result.category());
    }

    @Test
    @DisplayName("긍정 키워드만 있으면 POSITIVE (카테고리 없음)")
    void classifiesPositive() {
        ArticleSentiment result = analyzer.analyze("삼성전자 사상최대 실적", "영업이익 신기록");
        assertEquals(Sentiment.POSITIVE, result.sentiment());
        assertNull(result.category());
    }

    @Test
    @DisplayName("부정·긍정 키워드 모두 없으면 NEUTRAL")
    void classifiesNeutral() {
        ArticleSentiment result = analyzer.analyze("삼성전자 신규 임원 인사", "정기 조직 개편");
        assertEquals(Sentiment.NEUTRAL, result.sentiment());
    }

    @Test
    @DisplayName("부정이 긍정보다 우선 — 부정 키워드가 있으면 NEGATIVE")
    void negativeWinsOverPositive() {
        ArticleSentiment result = analyzer.analyze("사상최대 실적에도 분식 논란", null);
        assertEquals(Sentiment.NEGATIVE, result.sentiment());
        assertEquals(IssueCategory.FINANCIAL, result.category());
    }
}
