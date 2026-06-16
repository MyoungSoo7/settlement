-- V1: 선정산 대출 핵심 테이블 (loan-service 자체 DB)
--
-- loan_advances   : 선지급 건 (애그리거트 루트)
-- loan_repayments : 정산 확정 시 차감 상환 기록. settlement_id UNIQUE 로 정산건당 1회 차감 보장(멱등).

CREATE TABLE IF NOT EXISTS loan_advances (
    id           BIGSERIAL      PRIMARY KEY,
    seller_id    BIGINT         NOT NULL,
    principal    NUMERIC(19, 2) NOT NULL,   -- 선지급 원금
    fee          NUMERIC(19, 2) NOT NULL,   -- 수수료(이자)
    outstanding  NUMERIC(19, 2) NOT NULL,   -- 미상환 잔액 (실행 시 principal+fee, 상환 시 감소)
    status       VARCHAR(20)    NOT NULL,   -- REQUESTED/APPROVED/DISBURSED/REPAID/REJECTED/OVERDUE/WRITTEN_OFF
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_loan_status CHECK (status IN
        ('REQUESTED','APPROVED','DISBURSED','REPAID','REJECTED','OVERDUE','WRITTEN_OFF'))
);

-- 셀러의 미상환(DISBURSED) 대출을 FIFO(id 오름차순=오래된 순)로 조회 — 상환 차감 핫패스
CREATE INDEX IF NOT EXISTS idx_loan_advances_seller_status
    ON loan_advances (seller_id, status, id);

CREATE TABLE IF NOT EXISTS loan_repayments (
    id            BIGSERIAL      PRIMARY KEY,
    settlement_id BIGINT         NOT NULL,   -- 차감 트리거가 된 정산 ID
    seller_id     BIGINT         NOT NULL,
    deducted      NUMERIC(19, 2) NOT NULL,   -- 해당 정산에서 차감된 총액(다건 대출 합산)
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),

    -- ★ 정산건당 차감 1회 — SettlementConfirmed 중복 수신 시 멱등(스키마 수준 최종 방어)
    CONSTRAINT uq_loan_repayment_settlement UNIQUE (settlement_id)
);

CREATE INDEX IF NOT EXISTS idx_loan_repayments_seller
    ON loan_repayments (seller_id);
