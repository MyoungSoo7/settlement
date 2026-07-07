package github.lms.lemuel.company.adapter.out.analysis;

import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** LLM 제공자 공용 라벨 매핑 (Claude·Gemini 공유). */
class SentimentLabelsTest {

    @Test
    @DisplayName("라벨 → 도메인 매핑 (부정은 카테고리, 알 수 없는 라벨/공백/소문자는 관대 처리)")
    void labelMapping() {
        assertEquals(Sentiment.POSITIVE, SentimentLabels.toSentiment("POSITIVE").sentiment());
        assertEquals(IssueCategory.FINANCIAL, SentimentLabels.toSentiment("NEGATIVE_FINANCIAL").category());
        assertEquals(IssueCategory.LEGAL, SentimentLabels.toSentiment(" negative_legal \n").category());
        assertEquals(IssueCategory.GOVERNANCE, SentimentLabels.toSentiment("NEGATIVE_GOVERNANCE").category());
        assertEquals(IssueCategory.LABOR, SentimentLabels.toSentiment("NEGATIVE_LABOR").category());
        assertEquals(IssueCategory.PRODUCT, SentimentLabels.toSentiment("NEGATIVE_PRODUCT").category());
        assertEquals(Sentiment.NEGATIVE, SentimentLabels.toSentiment("NEGATIVE_OTHER").sentiment());
        assertNull(SentimentLabels.toSentiment("NEGATIVE_OTHER").category());
        assertEquals(Sentiment.NEUTRAL, SentimentLabels.toSentiment("횡설수설").sentiment());
        assertEquals(Sentiment.NEUTRAL, SentimentLabels.toSentiment(null).sentiment());
    }

    @Test
    @DisplayName("userText 는 제목/요약을 합치고 null 은 빈 문자열로")
    void userText() {
        assertEquals("제목: A\n요약: B", SentimentLabels.userText("A", "B"));
        assertEquals("제목: A\n요약: ", SentimentLabels.userText("A", null));
    }
}
