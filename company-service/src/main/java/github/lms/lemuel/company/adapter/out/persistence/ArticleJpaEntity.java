package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "articles")
public class ArticleJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url_hash", nullable = false, unique = true, length = 64)
    private String urlHash;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ArticleSource source;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "summary", length = 2000)
    private String summary;

    @Column(name = "publisher", length = 200)
    private String publisher;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected ArticleJpaEntity() {
    }

    static ArticleJpaEntity fromDomain(Article article) {
        ArticleJpaEntity entity = new ArticleJpaEntity();
        entity.urlHash = article.urlHash();
        entity.stockCode = article.stockCode();
        entity.source = article.source();
        entity.title = article.title();
        entity.summary = article.summary();
        entity.publisher = article.publisher();
        entity.url = article.url();
        entity.publishedAt = article.publishedAt();
        entity.collectedAt = Instant.now();
        return entity;
    }

    Article toDomain() {
        return Article.rehydrate(urlHash, stockCode, source, title, summary, publisher, url, publishedAt);
    }
}
