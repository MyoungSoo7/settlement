-- V3: 기업 평판 스냅샷 (company-service, ADR 0023 Phase 2)
--
-- ★ INSERT-only 이력 테이블 — 저장 후 UPDATE 하지 않는다. 점수 산식이 바뀌어도 과거 스냅샷은
--   불변이라 "그 시점에 왜 이 등급이었나"를 재현할 수 있다(여신 연계 감사 요건, commission_rate
--   영구 보존과 같은 원칙). (stock_code, snapshot_date) UNIQUE 로 하루 1건 — 첫 스냅샷이 그날의
--   불변 기록으로 남고, 같은 날 재계산은 건너뛴다.
-- 카테고리별 부정 건수는 JSONB 대신 명시 컬럼 5개로 편다 — validate 친화적 + SQL 집계 용이.

CREATE TABLE IF NOT EXISTS reputation_scores (
    id             BIGSERIAL   PRIMARY KEY,
    stock_code     VARCHAR(6)  NOT NULL REFERENCES companies (stock_code),
    snapshot_date  DATE        NOT NULL,
    score          INT         NOT NULL,
    grade          VARCHAR(1)  NOT NULL,
    article_count  INT         NOT NULL,
    positive_count INT         NOT NULL,
    negative_count INT         NOT NULL,
    neutral_count  INT         NOT NULL,
    financial_cnt  INT         NOT NULL DEFAULT 0,
    legal_cnt      INT         NOT NULL DEFAULT 0,
    governance_cnt INT         NOT NULL DEFAULT 0,
    labor_cnt      INT         NOT NULL DEFAULT 0,
    product_cnt    INT         NOT NULL DEFAULT 0,
    calculated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_reputation_stock_date UNIQUE (stock_code, snapshot_date),
    CONSTRAINT chk_reputation_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT chk_reputation_grade CHECK (grade IN ('A', 'B', 'C', 'D', 'E'))
);

CREATE INDEX IF NOT EXISTS idx_reputation_stock_date
    ON reputation_scores (stock_code, snapshot_date DESC);
