-- V35: Double-Entry Ledger tables
-- accounts: Chart of Accounts
CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_type ON accounts(type);
CREATE INDEX idx_accounts_code ON accounts(code);

-- journal_entries: 분개 전표 (Aggregate Root)
CREATE TABLE journal_entries (
    id              BIGSERIAL PRIMARY KEY,
    entry_type      VARCHAR(50) NOT NULL,
    reference_type  VARCHAR(30) NOT NULL,
    reference_id    BIGINT NOT NULL,
    description     TEXT,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_journal_entries_reference ON journal_entries(reference_type, reference_id);
CREATE INDEX idx_journal_entries_entry_type ON journal_entries(entry_type);
CREATE INDEX idx_journal_entries_created_at ON journal_entries(created_at);

-- ledger_lines: 차변/대변 개별 라인
CREATE TABLE ledger_lines (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entries(id),
    account_id       BIGINT NOT NULL REFERENCES accounts(id),
    side             VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount           NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_lines_journal_entry ON ledger_lines(journal_entry_id);
CREATE INDEX idx_ledger_lines_account ON ledger_lines(account_id);
CREATE INDEX idx_ledger_lines_account_created ON ledger_lines(account_id, created_at);

-- account_balance_snapshots: 잔액 스냅샷 (snapshot + delta 패턴)
CREATE TABLE account_balance_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    balance     NUMERIC(15,2) NOT NULL,
    snapshot_at DATE NOT NULL,
    CONSTRAINT uq_account_snapshot UNIQUE (account_id, snapshot_at)
);

CREATE INDEX idx_balance_snapshots_account ON account_balance_snapshots(account_id, snapshot_at DESC);

-- Seed: 기본 플랫폼 계정 생성
INSERT INTO accounts (code, name, type) VALUES
    ('PLATFORM_CASH', '플랫폼 보유 현금', 'ASSET'),
    ('PLATFORM_COMMISSION', '플랫폼 수수료 수익', 'REVENUE'),
    ('BANK_TRANSFER_PENDING', '은행 이체 진행중', 'ASSET'),
    ('REFUND_EXPENSE', '환불 비용', 'EXPENSE');
