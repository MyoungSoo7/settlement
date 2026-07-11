package github.lms.lemuel.investment.domain;

import java.time.Instant;

/** 기업 뉴스 기사 메타 — company-service 공개 API 의 도메인 표현(본문 없음 — 제목·요약·링크만). */
public record NewsArticleSummary(String title, String summary, String url, Instant publishedAt) {
}
