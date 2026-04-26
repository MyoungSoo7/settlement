-- V38: payouts 테이블 신설 + Settlement PAID 상태 추가
-- 정산 DONE → 셀러 송금 → PAID 흐름 활성화

-- 1. payouts 테이블
CREATE TABLE payouts (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    bank_transaction_id VARCHAR(100),
    failure_reason TEXT,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payout_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(id),
    CONSTRAINT chk_payouts_amount CHECK (amount > 0),
    CONSTRAINT chk_payouts_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

-- 정산 1건당 송금 1건 보장 (멱등성 + 비즈니스 invariant)
CREATE UNIQUE INDEX idx_payouts_settlement_id_unique ON payouts(settlement_id);

-- 조회 최적화
CREATE INDEX idx_payouts_seller_id ON payouts(seller_id);
CREATE INDEX idx_payouts_status ON payouts(status);
CREATE INDEX idx_payouts_requested_at ON payouts(requested_at);

-- 2. Settlement status에 PAID 추가
ALTER TABLE settlements DROP CONSTRAINT IF EXISTS chk_settlements_status;
ALTER TABLE settlements
    ADD CONSTRAINT chk_settlements_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'DONE', 'PAID', 'FAILED', 'CANCELED'));

COMMENT ON TABLE payouts IS '셀러 송금 이력 — 정산 DONE → 펌뱅킹 송금 → SUCCEEDED/FAILED';
COMMENT ON COLUMN settlements.status IS '정산 상태: REQUESTED|PROCESSING|DONE|PAID|FAILED|CANCELED';
