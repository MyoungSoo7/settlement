package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
