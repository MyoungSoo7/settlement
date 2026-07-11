-- V1: 계정계 핵심 테이블 (account-service 자체 DB lemuel_account)
--
-- account_entries : 전사 복식부기 GL 분개. 한 행 = 차변 1 계정 + 대변 1 계정 + 금액(양수).
--   loan·investment·settlement 이벤트를 소비해 적재하며, 자연키 (source_topic, ref_type, ref_id) 로
--   중복 수신을 멱등 차단한다(컨슈머 processed_events 와 이중 방어).

CREATE TABLE IF NOT EXISTS account_entries (
    id             BIGSERIAL      PRIMARY KEY,
    owner_type     VARCHAR(20)    NOT NULL,   -- SELLER / CORPORATE
    owner_id       VARCHAR(64)    NOT NULL,   -- sellerId 문자열 또는 stockCode
    debit_account  VARCHAR(40)    NOT NULL,   -- GlAccount (차변)
    credit_account VARCHAR(40)    NOT NULL,   -- GlAccount (대변)
    amount         NUMERIC(19, 2) NOT NULL,   -- 전표 금액 (차변=대변=amount)
    ref_type       VARCHAR(40)    NOT NULL,   -- SETTLEMENT_CREATED/CONFIRMED, LOAN_DISBURSED/REPAID, CORP_LOAN_DISBURSED, INVESTMENT_EXECUTED
    ref_id         VARCHAR(64)    NOT NULL,   -- settlementId / loanId / orderId
    source_topic   VARCHAR(100)   NOT NULL,   -- 파생 이벤트 토픽
    occurred_at    TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_account_entry_amount CHECK (amount > 0),
    CONSTRAINT chk_account_owner_type   CHECK (owner_type IN ('SELLER', 'CORPORATE')),
    -- ★ 전표 자연키 — 동일 이벤트 재수신 시 분개 1회 (스키마 수준 최종 멱등 방어)
    CONSTRAINT uq_account_entry_natural UNIQUE (source_topic, ref_type, ref_id)
);

-- owner 원장 조회 핫패스 (최신순)
CREATE INDEX IF NOT EXISTS idx_account_entries_owner
    ON account_entries (owner_type, owner_id, id DESC);

-- refType 별 집계(대출/투자/정산) 조회
CREATE INDEX IF NOT EXISTS idx_account_entries_ref_type
    ON account_entries (ref_type);
