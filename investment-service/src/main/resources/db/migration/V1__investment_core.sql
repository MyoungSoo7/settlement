-- V1: 투자 서비스 핵심 테이블 (investment-service 자체 DB lemuel_investment)
--
-- investment_orders   : 투자 주문 (애그리거트 루트). 신청 시점 투자점수·등급 스냅샷 보존.
-- seller_funding_view : settlement 확정 정산금의 로컬 투영(재원). settlement_id UNIQUE=멱등 UPSERT.

CREATE TABLE IF NOT EXISTS investment_orders (
    id             BIGSERIAL      PRIMARY KEY,
    seller_id      BIGINT         NOT NULL,
    stock_code     VARCHAR(6)     NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    score_at_order INTEGER        NOT NULL,           -- 신청 시점 투자점수(0~100)
    grade_at_order VARCHAR(3)     NOT NULL,           -- 신청 시점 등급(AAA~CCC)
    status         VARCHAR(20)    NOT NULL,           -- REQUESTED/APPROVED/EXECUTED/REJECTED/CANCELED
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_investment_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_investment_status CHECK (status IN
        ('REQUESTED','APPROVED','EXECUTED','REJECTED','CANCELED'))
);

-- 셀러별 주문 조회 + 집행 완료(EXECUTED) 합계 집계 핫패스
CREATE INDEX IF NOT EXISTS idx_investment_orders_seller_status
    ON investment_orders (seller_id, status, id);

CREATE TABLE IF NOT EXISTS seller_funding_view (
    settlement_id BIGINT         PRIMARY KEY,          -- settlement 측 정산 ID (이벤트로 수신)
    seller_id     BIGINT         NOT NULL,
    amount        NUMERIC(19, 2) NOT NULL,
    status        VARCHAR(20)    NOT NULL DEFAULT 'CONFIRMED',
    updated_at    TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_sfv_amount_nonneg CHECK (amount >= 0),
    CONSTRAINT chk_sfv_status CHECK (status IN ('CONFIRMED'))
);

-- 셀러별 확정 재원 합계 조회 핫패스
CREATE INDEX IF NOT EXISTS idx_sfv_seller_status
    ON seller_funding_view (seller_id, status);
