package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.Article;

import java.time.Instant;

public record ArticleResponse(String title, String summary, String publisher, String url,
                              String source, Instant publishedAt) {

    public static ArticleResponse from(Article article) {
        return new ArticleResponse(article.title(), article.summary(), article.publisher(),
                article.url(), article.source().name(), article.publishedAt());
    }
}
