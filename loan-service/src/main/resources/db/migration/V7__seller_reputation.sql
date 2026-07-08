-- V7: 셀러(법인)별 평판 등급 프로젝션 (ADR 0023 Phase 3 후속)
--
-- CompanyReputationChanged 이벤트에 동봉된 linked sellerId 로 적재한다. sellerId 가 PK 라 멱등 UPSERT.
-- 신용 한도 haircut(CreditPolicy) 이 이 등급을 조회한다. (schema=opslab — loan 자체 DB.)

CREATE TABLE IF NOT EXISTS seller_reputation (
    seller_id  BIGINT      PRIMARY KEY,          -- 셀러 ID (settlement/loan 공용)
    stock_code VARCHAR(6)  NOT NULL,             -- 연결된 기업 종목코드
    score      INT         NOT NULL,
    grade      VARCHAR(1)  NOT NULL,             -- A~E
    updated_at TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_seller_reputation_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT chk_seller_reputation_grade CHECK (grade IN ('A', 'B', 'C', 'D', 'E'))
);

CREATE INDEX IF NOT EXISTS idx_seller_reputation_stock ON seller_reputation (stock_code);
