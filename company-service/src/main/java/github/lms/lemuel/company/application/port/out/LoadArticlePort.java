package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSource;

import java.util.List;

public interface LoadArticlePort {

    /** 기업별 기사 페이지 조회 — source 가 null 이면 전체. 발행일시 내림차순. */
    PageResult findByCompany(String stockCode, ArticleSource source, int page, int size);

    /** 평판 산정용 — 발행일시가 since 이후인 기사 전체 (발행일시 null 은 수집일시 기준으로 포함). */
    List<Article> findForScoring(String stockCode, java.time.Instant since);

    record PageResult(List<Article> content, long totalElements) {
    }
}
