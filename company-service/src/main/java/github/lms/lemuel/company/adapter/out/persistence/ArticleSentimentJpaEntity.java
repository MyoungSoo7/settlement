package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.Sentiment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 기사 감성 캐시 엔티티 — 복합키 (url_hash, provider). 도메인 {@link ArticleSentiment} 를 그대로 재현한다.
 * persistence 어댑터라 단위 커버리지 게이트 제외(통합 테스트로 검증).
 */
@Entity
@Table(name = "article_sentiment")
@IdClass(ArticleSentimentJpaEntity.Key.class)
public class ArticleSentimentJpaEntity {

    @Id
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Id
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "sentiment", nullable = false, length = 10)
    private String sentiment;

    @Column(name = "category", length = 20)
    private String category;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    protected ArticleSentimentJpaEntity() {
    }

    static ArticleSentimentJpaEntity of(String urlHash, String provider, ArticleSentiment s, Instant analyzedAt) {
        ArticleSentimentJpaEntity e = new ArticleSentimentJpaEntity();
        e.urlHash = urlHash;
        e.provider = provider;
        e.sentiment = s.sentiment().name();
        e.category = s.category() == null ? null : s.category().name();
        e.analyzedAt = analyzedAt;
        return e;
    }

    ArticleSentiment toDomain() {
        Sentiment s = Sentiment.valueOf(sentiment);
        IssueCategory c = category == null ? null : IssueCategory.valueOf(category);
        return new ArticleSentiment(s, c);
    }

    /** JPA 복합키 클래스 — (url_hash, provider). */
    public static class Key implements Serializable {
        private String urlHash;
        private String provider;

        public Key() {
        }

        public Key(String urlHash, String provider) {
            this.urlHash = urlHash;
            this.provider = provider;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(urlHash, key.urlHash) && Objects.equals(provider, key.provider);
        }

        @Override
        public int hashCode() {
            return Objects.hash(urlHash, provider);
        }
    }
}
