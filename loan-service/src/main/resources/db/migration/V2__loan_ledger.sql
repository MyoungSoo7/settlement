-- V2: loan 자체 복식부기 원장
--
-- settlement 의 ledger 와 분리된 loan 전용 원장 (MSA 경계 유지). 각 전표는 차변 1 + 대변 1 의
-- 균형 분개. 선지급/수수료인식/상환/대손을 기록한다.

CREATE TABLE IF NOT EXISTS loan_ledger_entries (
    id          BIGSERIAL      PRIMARY KEY,
    debit       VARCHAR(30)    NOT NULL,   -- 차변 계정
    credit      VARCHAR(30)    NOT NULL,   -- 대변 계정
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    ref_type    VARCHAR(20)    NOT NULL,   -- DISBURSE / FEE / REPAYMENT
    ref_id      BIGINT         NOT NULL,   -- loanId 또는 settlementId
    created_at  TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_loan_ledger_ref
    ON loan_ledger_entries (ref_type, ref_id);
