package github.lms.lemuel.company.domain;

/** 기사 수집원. Phase 1 은 NAVER_NEWS 만 사용, DART 공시·RSS 는 Phase 2+ (ADR 0023). */
public enum ArticleSource {
    NAVER_NEWS,
    DART_DISCLOSURE,
    RSS
}
