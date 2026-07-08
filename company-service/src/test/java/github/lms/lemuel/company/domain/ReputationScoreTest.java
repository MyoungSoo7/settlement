package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReputationScoreTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 7);
    private static final Instant AT = Instant.parse("2026-07-07T00:00:00Z");

    private static ReputationScore compute(List<ArticleSentiment> sentiments) {
        return ReputationScore.compute("005930", DATE, sentiments, AT);
    }

    @Test
    @DisplayName("기사가 없으면 감점 신호가 없어 100점·A")
    void emptyIsHundred() {
        ReputationScore score = compute(List.of());
        assertEquals(100, score.score());
        assertEquals(ReputationGrade.A, score.grade());
        assertEquals(0, score.articleCount());
    }

    @Test
    @DisplayName("긍정·중립만 있으면 감점 없음 → 100점")
    void noNegativeNoPenalty() {
        ReputationScore score = compute(List.of(
                ArticleSentiment.positive(), ArticleSentiment.neutral(), ArticleSentiment.positive()));
        assertEquals(100, score.score());
        assertEquals(2, score.positiveCount());
        assertEquals(1, score.neutralCount());
        assertEquals(0, score.negativeCount());
    }

    @Test
    @DisplayName("전 기사가 최대 가중(FINANCIAL=3) 부정이면 0점·E")
    void allMaxWeightNegativeIsZero() {
        ReputationScore score = compute(List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL),
                ArticleSentiment.negative(IssueCategory.LEGAL)));
        assertEquals(0, score.score());
        assertEquals(ReputationGrade.E, score.grade());
        assertEquals(2, score.negativeCount());
    }

    @Test
    @DisplayName("가중 부정합 / (전체 × MAX_WEIGHT) 로 감점 — 카테고리 건수도 집계")
    void weightedPenaltyAndCategoryTally() {
        // 4건: FINANCIAL(3) + PRODUCT(2) + 미분류부정(1) + 긍정(0) → 가중합 6, 분모 4*3=12 → 100-50=50
        ReputationScore score = compute(List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL),
                ArticleSentiment.negative(IssueCategory.PRODUCT),
                ArticleSentiment.negative(null),
                ArticleSentiment.positive()));
        assertEquals(50, score.score());
        assertEquals(ReputationGrade.C, score.grade());
        assertEquals(3, score.negativeCount());
        assertEquals(1, score.positiveCount());
        assertEquals(1, score.negativeCountOf(IssueCategory.FINANCIAL));
        assertEquals(1, score.negativeCountOf(IssueCategory.PRODUCT));
        assertEquals(0, score.negativeCountOf(IssueCategory.LEGAL));
    }

    @Test
    @DisplayName("rehydrate — 저장값을 그대로 복원하고 카테고리 건수를 노출한다")
    void rehydratePreservesStoredValues() {
        ReputationScore score = ReputationScore.rehydrate("005930", DATE, 65, ReputationGrade.B,
                4, 1, 2, 1, Map.of(IssueCategory.LEGAL, 1, IssueCategory.PRODUCT, 1), AT);

        assertEquals("005930", score.stockCode());
        assertEquals(DATE, score.snapshotDate());
        assertEquals(65, score.score());
        assertEquals(ReputationGrade.B, score.grade());
        assertEquals(4, score.articleCount());
        assertEquals(1, score.positiveCount());
        assertEquals(2, score.negativeCount());
        assertEquals(1, score.neutralCount());
        assertEquals(AT, score.calculatedAt());
        assertEquals(1, score.negativeCountOf(IssueCategory.LEGAL));
        assertEquals(0, score.negativeCountOf(IssueCategory.FINANCIAL));
        assertEquals(2, score.negativeByCategory().size());
    }

    @Test
    @DisplayName("compute — 종목코드가 6자리가 아니면 예외")
    void computeRejectsInvalidStockCode() {
        assertThrows(IllegalArgumentException.class,
                () -> ReputationScore.compute("12345", DATE, List.of(), AT));
    }

    @Test
    @DisplayName("compute — snapshotDate·calculatedAt 은 필수")
    void computeRejectsNullDates() {
        assertThrows(IllegalArgumentException.class,
                () -> ReputationScore.compute("005930", null, List.of(), AT));
        assertThrows(IllegalArgumentException.class,
                () -> ReputationScore.compute("005930", DATE, List.of(), null));
    }

    @Test
    @DisplayName("등급 경계 — 80/60/40/20")
    void gradeBoundaries() {
        assertEquals(ReputationGrade.A, ReputationGrade.fromScore(80));
        assertEquals(ReputationGrade.B, ReputationGrade.fromScore(79));
        assertEquals(ReputationGrade.B, ReputationGrade.fromScore(60));
        assertEquals(ReputationGrade.C, ReputationGrade.fromScore(59));
        assertEquals(ReputationGrade.D, ReputationGrade.fromScore(39));
        assertEquals(ReputationGrade.E, ReputationGrade.fromScore(19));
    }
}
