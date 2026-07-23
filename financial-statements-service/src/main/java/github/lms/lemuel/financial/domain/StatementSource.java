package github.lms.lemuel.financial.domain;

/** 재무제표 데이터 출처. */
public enum StatementSource {
    /** (레거시) 제거된 Flyway 시드의 잔존 행 호환용 — DART 동기화가 같은 (기업, 연도, 구분) upsert 로 대체한다. */
    SEED,
    /** 금감원 OpenDART 수집 실데이터 */
    DART
}
