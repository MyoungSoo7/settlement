package github.lms.lemuel.company.application.port.in;

import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSource;

import java.util.List;

/** 기업별 기사 목록 조회 (발행일시 내림차순). */
public interface GetArticlesUseCase {

    ArticlePage byCompany(String stockCode, ArticleSource source, int page, int size);

    record ArticlePage(List<Article> content, int page, int size, long totalElements) {
        public int totalPages() {
            return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        }
    }
}
