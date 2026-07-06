package github.lms.lemuel.company.application.port.out;

import java.time.Instant;
import java.util.List;

/** 외부 뉴스 검색 API 클라이언트 포트 (Phase 1 구현체: 네이버 뉴스 검색). */
public interface NewsClientPort {

    /** API 자격증명 미설정이면 false — 수집 트리거는 이때 409 로 거절한다. */
    boolean isConfigured();

    /** 기업명으로 최신 뉴스 검색. 항목의 title/summary 는 wire 포맷(HTML 태그 등)이 정리된 상태다. */
    List<NewsItem> fetchNews(String companyName);

    record NewsItem(String title, String summary, String publisher, String url, Instant publishedAt) {
    }
}
