package github.lms.lemuel.company.domain;

/**
 * 기사 1건의 감성 분석 결과 (값 객체).
 *
 * @param sentiment 극성
 * @param category  부정 기사의 이슈 분류 — POSITIVE/NEUTRAL 이거나 미분류 부정이면 null
 */
public record ArticleSentiment(Sentiment sentiment, IssueCategory category) {

    public ArticleSentiment {
        if (sentiment == null) {
            throw new IllegalArgumentException("sentiment 는 필수입니다");
        }
        if (sentiment != Sentiment.NEGATIVE && category != null) {
            throw new IllegalArgumentException("이슈 분류는 부정 기사에만 붙는다: " + sentiment);
        }
    }

    public static ArticleSentiment positive() {
        return new ArticleSentiment(Sentiment.POSITIVE, null);
    }

    public static ArticleSentiment neutral() {
        return new ArticleSentiment(Sentiment.NEUTRAL, null);
    }

    public static ArticleSentiment negative(IssueCategory category) {
        return new ArticleSentiment(Sentiment.NEGATIVE, category);
    }

    /** 이 기사가 평판 점수에서 깎는 가중치 (부정이 아니면 0). */
    public int penaltyWeight() {
        if (sentiment != Sentiment.NEGATIVE) {
            return 0;
        }
        return category == null ? IssueCategory.UNCATEGORIZED_WEIGHT : category.weight();
    }
}
