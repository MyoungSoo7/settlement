-- V1: 데이터소스 카탈로그 + 수집 레코드 (common-data-service 자체 DB)
--
-- data_sources : 등록된 공공데이터포털(data.go.kr) OpenAPI. endpoint·기본 파라미터·자연키
--                필드만 등록하면 코드 변경 없이 수집 대상이 된다.
-- data_records : 수집 아이템. payload 는 JSON 원문 보존(범용 커넥터 — 특화 스키마 없음),
--                (source_id, record_key) UNIQUE upsert 로 재수집 멱등.

CREATE TABLE IF NOT EXISTS data_sources (
    id             BIGSERIAL    PRIMARY KEY,
    code           VARCHAR(50)  NOT NULL,             -- 소문자 슬러그 (도메인이 패턴 강제)
    name           VARCHAR(100) NOT NULL,
    endpoint       VARCHAR(500) NOT NULL,             -- 전체 URL (https://apis.data.go.kr/..)
    default_params TEXT         NOT NULL DEFAULT '{}', -- 항상 붙는 쿼리 파라미터 JSON (_type 등)
    key_fields     VARCHAR(300),                       -- 자연키 필드명 CSV — NULL 이면 payload 해시 키
    page_size      INT          NOT NULL DEFAULT 100,  -- numOfRows
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    description    VARCHAR(500),
    -- 엔티티가 Instant 매핑(TIMESTAMP_UTC)이라 TIMESTAMPTZ — ddl-auto=validate 통과 조건
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ds_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS data_records (
    id           BIGSERIAL    PRIMARY KEY,
    source_id    BIGINT       NOT NULL REFERENCES data_sources (id),
    record_key   VARCHAR(300) NOT NULL,               -- keyFields 값 조인 or payload SHA-256
    payload      TEXT         NOT NULL,               -- 아이템 JSON 원문
    collected_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_dr_source_key UNIQUE (source_id, record_key)
);

CREATE INDEX IF NOT EXISTS idx_dr_source_collected ON data_records (source_id, collected_at DESC, id DESC);
