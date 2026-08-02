-- 데이터소스 제공처 구분 컬럼 — data.go.kr 외에 서울 열린데이터광장(SEOUL_OPENAPI) 봉투 지원.
-- 기존 행은 전부 data.go.kr 등록분이므로 기본값 'DATA_GO_KR' 로 채운다.

ALTER TABLE data_sources
    ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'DATA_GO_KR';
