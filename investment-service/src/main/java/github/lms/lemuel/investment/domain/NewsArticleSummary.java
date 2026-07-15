package github.lms.lemuel.investment.domain;

import java.time.Instant;

/** 기업 뉴스 기사 메타 — company-service 공개 API 의 도메인 표현(본문 없음 — 제목·요약·링크만). */
public record NewsArticleSummary(String title, String summary, String url, Instant publishedAt) {

    /**
     * 이 기사가 주어진 키워드를 언급하는가 — 제목·요약 중 하나라도 포함하면 참(null 필드/키워드는 미포함).
     * 본문을 저장하지 않으므로 판정 대상은 제목·요약뿐이다.
     */
    public boolean mentions(String keyword) {
        return contains(title, keyword) || contains(summary, keyword);
    }

    private static boolean contains(String text, String keyword) {
        return text != null && keyword != null && text.contains(keyword);
    }
}
