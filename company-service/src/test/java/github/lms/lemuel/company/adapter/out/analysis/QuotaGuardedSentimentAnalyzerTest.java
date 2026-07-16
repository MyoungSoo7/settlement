package github.lms.lemuel.company.adapter.out.analysis;

import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaGuardedSentimentAnalyzerTest {

    private GeminiSentimentProperties props(int dailyQuota, long minIntervalMs) {
        return new GeminiSentimentProperties("k", null, null, 0, dailyQuota, minIntervalMs);
    }

    @Test
    @DisplayName("일일 상한 내에서는 Gemini 델리게이트로 위임한다")
    void delegatesWithinQuota() {
        GeminiSentimentAnalyzer delegate = mock(GeminiSentimentAnalyzer.class);
        when(delegate.analyze(any(), any())).thenReturn(ArticleSentiment.positive());
        QuotaGuardedSentimentAnalyzer guard = new QuotaGuardedSentimentAnalyzer(delegate, props(3, 0));

        ArticleSentiment r1 = guard.analyze("제목", "요약");
        ArticleSentiment r2 = guard.analyze("제목", "요약");

        assertEquals(Sentiment.POSITIVE, r1.sentiment());
        assertEquals(Sentiment.POSITIVE, r2.sentiment());
        verify(delegate, times(2)).analyze(any(), any());
    }

    @Test
    @DisplayName("일일 상한 도달 후에는 델리게이트를 부르지 않고 키워드로 폴백한다")
    void fallsBackToKeywordWhenQuotaExhausted() {
        GeminiSentimentAnalyzer delegate = mock(GeminiSentimentAnalyzer.class);
        // 델리게이트는 항상 POSITIVE — 폴백(키워드)과 구분되도록
        when(delegate.analyze(any(), any())).thenReturn(ArticleSentiment.positive());
        QuotaGuardedSentimentAnalyzer guard = new QuotaGuardedSentimentAnalyzer(delegate, props(2, 0));

        guard.analyze("제목", "요약");           // 1 — delegate
        guard.analyze("제목", "요약");           // 2 — delegate
        // 3 — 상한 초과 → 키워드 폴백. "소송"은 LEGAL 부정 키워드
        ArticleSentiment overflow = guard.analyze("소송 제기", "요약");
        // 4 — 상한 여전히 초과 (warnedToday 분기 커버)
        ArticleSentiment overflow2 = guard.analyze("리콜 사태", "요약");

        assertEquals(Sentiment.NEGATIVE, overflow.sentiment());
        assertEquals(IssueCategory.LEGAL, overflow.category());
        assertEquals(Sentiment.NEGATIVE, overflow2.sentiment());
        assertEquals(IssueCategory.PRODUCT, overflow2.category());
        verify(delegate, times(2)).analyze(any(), any());   // 상한만큼만 델리게이트
    }

    @Test
    @DisplayName("호출 간 최소 간격(스로틀)이 있어도 결과는 델리게이트 값이다")
    void throttleDoesNotChangeResult() {
        GeminiSentimentAnalyzer delegate = mock(GeminiSentimentAnalyzer.class);
        when(delegate.analyze(any(), any())).thenReturn(ArticleSentiment.neutral());
        QuotaGuardedSentimentAnalyzer guard = new QuotaGuardedSentimentAnalyzer(delegate, props(10, 15));

        ArticleSentiment r1 = guard.analyze("제목", "요약");
        ArticleSentiment r2 = guard.analyze("제목", "요약");   // 두 번째는 최소 간격 대기 경로

        assertEquals(Sentiment.NEUTRAL, r1.sentiment());
        assertEquals(Sentiment.NEUTRAL, r2.sentiment());
        verify(delegate, times(2)).analyze(any(), any());
    }
}
