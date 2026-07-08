-- V1: 기업 마스터 + 뉴스 기사 메타데이터 (company-service 자체 DB, ADR 0023)
--
-- companies : 뉴스·평판 조회 대상 기업. 비즈니스 키는 종목코드(6자리) —
--             financial-statements-service 와 공용 식별자. corp_code 는 시드 단계에서 NULL.
-- articles  : 기사 메타데이터만 저장 — ★ 저작권 제약으로 본문 전문은 저장하지 않는다
--             (제목·요약 발췌·언론사·발행일시·원문 URL 이 전부).
--             url_hash(정규화 URL 의 SHA-256) UNIQUE 가 재수집·중복 수집 멱등 방어선.

CREATE TABLE IF NOT EXISTS companies (
    stock_code VARCHAR(6)   PRIMARY KEY,
    corp_code  VARCHAR(8)   UNIQUE,          -- DART 고유번호 (Phase 3+ 동기화 대상)
    name       VARCHAR(200) NOT NULL,
    market     VARCHAR(10)  NOT NULL DEFAULT 'KOSPI',
    -- 엔티티가 Instant 매핑(TIMESTAMP_UTC)이라 TIMESTAMPTZ — ddl-auto=validate 통과 조건
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_companies_name ON companies (name);

CREATE TABLE IF NOT EXISTS articles (
    id           BIGSERIAL     PRIMARY KEY,
    url_hash     VARCHAR(64)   NOT NULL UNIQUE,   -- 정규화 원문 URL 의 SHA-256 hex — 멱등 키
    stock_code   VARCHAR(6)    NOT NULL REFERENCES companies (stock_code),
    source       VARCHAR(20)   NOT NULL,
    title        VARCHAR(500)  NOT NULL,
    summary      VARCHAR(2000),                   -- 발췌 요약 — 본문 전문 아님 (저작권)
    publisher    VARCHAR(200),
    url          VARCHAR(1000) NOT NULL,
    published_at TIMESTAMPTZ,
    collected_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_articles_source CHECK (source IN ('NAVER_NEWS', 'DART_DISCLOSURE', 'RSS'))
);

CREATE INDEX IF NOT EXISTS idx_articles_stock_published
    ON articles (stock_code, published_at DESC NULLS LAST, id DESC);
