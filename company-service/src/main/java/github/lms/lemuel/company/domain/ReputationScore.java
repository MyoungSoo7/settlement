package github.lms.lemuel.company.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 기업별 평판 스냅샷 — 특정 일자의 뉴스 감성 집계 결과.
 *
 * <p>★ INSERT-only 스냅샷(ADR 0023): 저장 후 절대 UPDATE 하지 않는다. 점수 산식이 바뀌어도
 * 과거 스냅샷은 불변이라 "그 시점에 왜 이 등급이었나"를 재현할 수 있다(여신 연계 시 감사 요건).
 * 이는 정산의 {@code commission_rate} 영구 보존과 같은 이력 보존 원칙이다.
 *
 * <p>점수 산식 v1(룰 기반): {@code score = 100 - round(100 * 가중부정합 / (전체 * MAX_WEIGHT))}.
 * 부정 기사만 카테고리 가중치로 감점하고, 긍정·중립은 감점하지 않는다. 전 기사가 최대 가중
 * 부정이면 0점, 부정이 없으면 100점.
 */
public class ReputationScore {

    private final String stockCode;
    private final LocalDate snapshotDate;
    private final int score;
    private final ReputationGrade grade;
    private final int articleCount;
    private final int positiveCount;
    private final int negativeCount;
    private final int neutralCount;
    private final Map<IssueCategory, Integer> negativeByCategory;
    private final Instant calculatedAt;

    private ReputationScore(String stockCode, LocalDate snapshotDate, int score, ReputationGrade grade,
                            int articleCount, int positiveCount, int negativeCount, int neutralCount,
                            Map<IssueCategory, Integer> negativeByCategory, Instant calculatedAt) {
        this.stockCode = stockCode;
        this.snapshotDate = snapshotDate;
        this.score = score;
        this.grade = grade;
        this.articleCount = articleCount;
        this.positiveCount = positiveCount;
        this.negativeCount = negativeCount;
        this.neutralCount = neutralCount;
        this.negativeByCategory = negativeByCategory;
        this.calculatedAt = calculatedAt;
    }

    /** 분석 결과 목록에서 스냅샷을 계산한다. 빈 목록이면 감점 신호가 없어 100점(A). */
    public static ReputationScore compute(String stockCode, LocalDate snapshotDate,
                                          List<ArticleSentiment> sentiments, Instant calculatedAt) {
        if (stockCode == null || stockCode.length() != 6) {
            throw new IllegalArgumentException("종목코드는 6자리여야 합니다: " + stockCode);
        }
        if (snapshotDate == null || calculatedAt == null) {
            throw new IllegalArgumentException("snapshotDate·calculatedAt 은 필수입니다");
        }
        int positive = 0;
        int negative = 0;
        int neutral = 0;
        int weightedPenalty = 0;
        Map<IssueCategory, Integer> byCategory = new EnumMap<>(IssueCategory.class);
        for (ArticleSentiment s : sentiments) {
            switch (s.sentiment()) {
                case POSITIVE -> positive++;
                case NEUTRAL -> neutral++;
                case NEGATIVE -> {
                    negative++;
                    weightedPenalty += s.penaltyWeight();
                    if (s.category() != null) {
                        byCategory.merge(s.category(), 1, Integer::sum);
                    }
                }
            }
        }
        int total = sentiments.size();
        int score = total == 0
                ? 100
                : Math.max(0, Math.min(100,
                        100 - (int) Math.round(100.0 * weightedPenalty / (total * IssueCategory.MAX_WEIGHT))));
        return new ReputationScore(stockCode, snapshotDate, score, ReputationGrade.fromScore(score),
                total, positive, negative, neutral, byCategory, calculatedAt);
    }

    /** 영속 계층 재구성용. */
    public static ReputationScore rehydrate(String stockCode, LocalDate snapshotDate, int score,
                                            ReputationGrade grade, int articleCount, int positiveCount,
                                            int negativeCount, int neutralCount,
                                            Map<IssueCategory, Integer> negativeByCategory, Instant calculatedAt) {
        return new ReputationScore(stockCode, snapshotDate, score, grade, articleCount,
                positiveCount, negativeCount, neutralCount,
                new EnumMap<>(negativeByCategory), calculatedAt);
    }

    public int negativeCountOf(IssueCategory category) {
        return negativeByCategory.getOrDefault(category, 0);
    }

    public String stockCode() {
        return stockCode;
    }

    public LocalDate snapshotDate() {
        return snapshotDate;
    }

    public int score() {
        return score;
    }

    public ReputationGrade grade() {
        return grade;
    }

    public int articleCount() {
        return articleCount;
    }

    public int positiveCount() {
        return positiveCount;
    }

    public int negativeCount() {
        return negativeCount;
    }

    public int neutralCount() {
        return neutralCount;
    }

    public Map<IssueCategory, Integer> negativeByCategory() {
        return Map.copyOf(negativeByCategory);
    }

    public Instant calculatedAt() {
        return calculatedAt;
    }
}
