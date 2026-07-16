package github.lms.lemuel.company.application.port.out;

import github.lms.lemuel.company.domain.ArticleSentiment;

import java.util.Optional;

/**
 * 기사 감성 분석 결과 캐시 포트 (ADR 0023 Phase 4 — LLM 비용 절감).
 *
 * <p>기사(title/summary)·{@code urlHash} 는 불변이라 {@code (urlHash, provider)} 별 감성은 영구 유효하다.
 * 평판 재계산이 매번 전체 기사를 재분석하던 것을, 캐시 히트로 대체해 LLM 재호출을 없앤다 —
 * 하루 신규 기사 수만큼만 분석한다. provider 를 키에 포함해 엔진 전환 시 캐시가 섞이지 않는다.
 */
public interface SentimentCachePort {

    /** 캐시된 감성 조회 — 없으면 빈 값(분석 필요). */
    Optional<ArticleSentiment> find(String urlHash, String provider);

    /** 분석 결과 캐시 저장 — 이미 있으면(동시 저장 레이스) 무시한다(멱등). */
    void save(String urlHash, String provider, ArticleSentiment sentiment);
}
