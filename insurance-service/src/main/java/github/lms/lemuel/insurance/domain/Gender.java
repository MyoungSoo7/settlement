package github.lms.lemuel.insurance.domain;

/**
 * 피보험자 성별 — 요율 테이블 조회 축.
 *
 * <p>V8 {@code premium_rate_tables.gender} CHECK ('M','F') 와 값이 1:1 로 일치한다.
 */
public enum Gender {

    /** 남성. */
    M,

    /** 여성. */
    F
}
