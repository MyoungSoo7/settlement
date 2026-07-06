-- V1: 코스피 상장사 + 요약 재무제표 (financial-statements-service 자체 DB)
--
-- companies            : 코스피 상장 기업. 비즈니스 키는 종목코드(6자리) — 시드 단계에선 DART
--                        고유번호(corp_code)를 알 수 없어 nullable, DART 기업 동기화가 채운다.
-- financial_statements : 연간(사업보고서) 요약 재무제표 6계정, 원 단위.
--                        (종목코드, 사업연도, 재무제표구분) UNIQUE upsert 로 SEED → DART 대체.

CREATE TABLE IF NOT EXISTS companies (
    stock_code VARCHAR(6)   PRIMARY KEY,
    corp_code  VARCHAR(8)   UNIQUE,          -- DART 고유번호 (동기화 후 채워짐)
    name       VARCHAR(200) NOT NULL,
    market     VARCHAR(10)  NOT NULL DEFAULT 'KOSPI',
    -- 엔티티가 Instant 매핑(TIMESTAMP_UTC)이라 TIMESTAMPTZ — ddl-auto=validate 통과 조건
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_companies_name ON companies (name);

CREATE TABLE IF NOT EXISTS financial_statements (
    id                BIGSERIAL   PRIMARY KEY,
    stock_code        VARCHAR(6)  NOT NULL REFERENCES companies (stock_code),
    fiscal_year       INT         NOT NULL,
    fs_div            VARCHAR(3)  NOT NULL DEFAULT 'CFS',   -- CFS 연결 / OFS 별도
    currency          VARCHAR(3)  NOT NULL DEFAULT 'KRW',
    revenue           NUMERIC(21),                          -- 원 단위 (이하 동일)
    operating_profit  NUMERIC(21),
    net_income        NUMERIC(21),
    total_assets      NUMERIC(21),
    total_liabilities NUMERIC(21),
    total_equity      NUMERIC(21),
    source            VARCHAR(10) NOT NULL,                 -- SEED(근사 샘플) / DART(실데이터)
    synced_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_fs_company_year_div UNIQUE (stock_code, fiscal_year, fs_div),
    CONSTRAINT chk_fs_div    CHECK (fs_div IN ('CFS', 'OFS')),
    CONSTRAINT chk_fs_source CHECK (source IN ('SEED', 'DART'))
);

CREATE INDEX IF NOT EXISTS idx_fs_stock_year ON financial_statements (stock_code, fiscal_year DESC);
