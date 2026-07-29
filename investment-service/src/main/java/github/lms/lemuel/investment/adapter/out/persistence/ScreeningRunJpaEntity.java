package github.lms.lemuel.investment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일일 스크리닝 실행 기록 — 시세 기준일이 PK 라 같은 기준일 재실행은 갱신(멱등)이다.
 *
 * <p>추천 세트와 분리해 두는 이유: 통과 종목 0건이면 추천 행이 남지 않아 "이미 이 기준일로 돌았다"를
 * 산출물로 표현할 수 없다.
 */
@Entity
@Table(name = "screening_runs")
public class ScreeningRunJpaEntity {

    @Id
    @Column(name = "quote_base_date", nullable = false)
    private LocalDate quoteBaseDate;

    @Column(name = "recommendation_count", nullable = false)
    private int recommendationCount;

    @Column(name = "screened_at", nullable = false)
    private LocalDateTime screenedAt;

    protected ScreeningRunJpaEntity() {
        // JPA
    }

    private ScreeningRunJpaEntity(LocalDate quoteBaseDate, int recommendationCount, LocalDateTime screenedAt) {
        this.quoteBaseDate = quoteBaseDate;
        this.recommendationCount = recommendationCount;
        this.screenedAt = screenedAt;
    }

    static ScreeningRunJpaEntity of(LocalDate quoteBaseDate, int recommendationCount, LocalDateTime screenedAt) {
        return new ScreeningRunJpaEntity(quoteBaseDate, recommendationCount, screenedAt);
    }

    public LocalDate getQuoteBaseDate() {
        return quoteBaseDate;
    }

    public int getRecommendationCount() {
        return recommendationCount;
    }

    public LocalDateTime getScreenedAt() {
        return screenedAt;
    }
}
