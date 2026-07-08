package github.lms.lemuel.company.application.port;

import github.lms.lemuel.company.application.port.in.GetArticlesUseCase.ArticlePage;
import github.lms.lemuel.company.application.port.out.LoadArticlePort.PageResult;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortRecordsTest {

    private static Article sample() {
        return Article.collect("005930", ArticleSource.NAVER_NEWS, "제목", "요약", "언론사",
                "https://news.example.com/1", Instant.parse("2026-07-07T00:00:00Z"));
    }

    @Test
    @DisplayName("ArticlePage.totalPages — 나머지가 있으면 올림한다")
    void totalPagesCeils() {
        ArticlePage page = new ArticlePage(List.of(sample()), 0, 10, 25);
        assertEquals(3, page.totalPages());
        assertEquals(0, page.page());
        assertEquals(10, page.size());
        assertEquals(25, page.totalElements());
    }

    @Test
    @DisplayName("ArticlePage.totalPages — 정확히 나눠떨어지면 올림 없음")
    void totalPagesExact() {
        assertEquals(2, new ArticlePage(List.of(), 0, 10, 20).totalPages());
    }

    @Test
    @DisplayName("ArticlePage.totalPages — size 0 이면 0 (0 나눗셈 방어)")
    void totalPagesZeroSize() {
        assertEquals(0, new ArticlePage(List.of(), 0, 0, 5).totalPages());
    }

    @Test
    @DisplayName("PageResult — content/totalElements 를 그대로 보관한다")
    void pageResultAccessors() {
        Article article = sample();
        PageResult result = new PageResult(List.of(article), 1);
        assertEquals(1, result.content().size());
        assertEquals(article, result.content().get(0));
        assertEquals(1, result.totalElements());
    }
}
