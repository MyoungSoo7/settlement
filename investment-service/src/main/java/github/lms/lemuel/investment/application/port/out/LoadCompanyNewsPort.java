package github.lms.lemuel.investment.application.port.out;

import github.lms.lemuel.investment.domain.NewsArticleSummary;

import java.util.List;
import java.util.Optional;

/** company-service 공개 API 로 기업 최근 뉴스 기사를 조회하는 아웃바운드 포트. */
public interface LoadCompanyNewsPort {

    /**
     * 최근 기사 목록. {@code Optional.empty()} 는 company-service 에 기업 미등록(404) —
     * "기사 0건"(빈 리스트)과 구분된다.
     */
    Optional<List<NewsArticleSummary>> loadRecentArticles(String stockCode);
}
