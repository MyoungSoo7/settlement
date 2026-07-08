package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArticleSentimentTest {

    @Test
    @DisplayName("positive/neutral 은 카테고리가 없고 감점 가중치 0")
    void positiveAndNeutralHaveNoPenalty() {
        assertNull(ArticleSentiment.positive().category());
        assertEquals(0, ArticleSentiment.positive().penaltyWeight());
        assertNull(ArticleSentiment.neutral().category());
        assertEquals(0, ArticleSentiment.neutral().penaltyWeight());
    }

    @Test
    @DisplayName("부정 기사는 카테고리 가중치로 감점한다")
    void categorizedNegativeUsesCategoryWeight() {
        assertEquals(IssueCategory.FINANCIAL.weight(),
                ArticleSentiment.negative(IssueCategory.FINANCIAL).penaltyWeight());
        assertEquals(IssueCategory.PRODUCT.weight(),
                ArticleSentiment.negative(IssueCategory.PRODUCT).penaltyWeight());
    }

    @Test
    @DisplayName("미분류 부정은 기본 가중치(UNCATEGORIZED_WEIGHT)로 감점한다")
    void uncategorizedNegativeUsesDefaultWeight() {
        ArticleSentiment s = ArticleSentiment.negative(null);
        assertEquals(Sentiment.NEGATIVE, s.sentiment());
        assertNull(s.category());
        assertEquals(IssueCategory.UNCATEGORIZED_WEIGHT, s.penaltyWeight());
    }

    @Test
    @DisplayName("sentiment 는 필수 — null 이면 예외")
    void nullSentimentRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ArticleSentiment(null, null));
    }

    @Test
    @DisplayName("이슈 분류는 부정 기사에만 붙는다 — 긍정+카테고리는 예외")
    void categoryOnlyOnNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArticleSentiment(Sentiment.POSITIVE, IssueCategory.LEGAL));
    }
}
