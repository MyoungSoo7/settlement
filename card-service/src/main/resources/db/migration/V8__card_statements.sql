-- V8: 명세서·청구·연체 (Phase 2 AC3)
--
-- card_statements: 청구주기별 명세서. billing_year_month 가 카드계정당 자연키.
-- statement_payments: 납부 멱등 레코드. payment_id 가 UNIQUE 자연키.
-- card_accounts: DELINQUENT 상태 추가.

-- card_accounts 상태 제약 확장(DELINQUENT 추가)
ALTER TABLE card_accounts DROP CONSTRAINT chk_card_account_status;
ALTER TABLE card_accounts ADD CONSTRAINT chk_card_account_status
    CHECK (status IN ('SCREENING', 'ACTIVE', 'SUSPENDED', 'DELINQUENT', 'CLOSED', 'REJECTED'));

-- 청구서(명세서) 테이블
-- 카드계정당 청구주기(YYYY-MM)별로 1개 생성된다.
-- 가용한도 계산: 가용한도 = masterLimit − Σ ACTIVE 홀드 (명세서는 별도 채권 관리)
CREATE TABLE card_statements (
    id                  BIGSERIAL       PRIMARY KEY,
    card_account_id     BIGINT          NOT NULL REFERENCES card_accounts(id),
    billing_year_month  VARCHAR(7)      NOT NULL,   -- 'YYYY-MM' 형식
    status              VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    total_amount        NUMERIC(19,2)   NOT NULL DEFAULT 0,
    paid_amount         NUMERIC(19,2)   NOT NULL DEFAULT 0,
    due_date            DATE,                        -- 납부 만기일
    closed_at           TIMESTAMPTZ,                 -- OPEN→CLOSED 시각
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_statement_status
        CHECK (status IN ('OPEN', 'CLOSED', 'PARTIALLY_PAID', 'PAID', 'DELINQUENT')),
    CONSTRAINT chk_statement_total_non_negative  CHECK (total_amount >= 0),
    CONSTRAINT chk_statement_paid_non_negative   CHECK (paid_amount >= 0)
);

-- 계정당 청구주기별 명세서는 1개
CREATE UNIQUE INDEX uq_card_statement_account_period
    ON card_statements (card_account_id, billing_year_month);

-- 연체 배치: CLOSED/PARTIALLY_PAID + dueDate 경과 명세서 조회
CREATE INDEX idx_card_statement_status_due ON card_statements (status, due_date);

-- 마감 배치: 청구주기별 OPEN 명세서 조회
CREATE INDEX idx_card_statement_period_status ON card_statements (billing_year_month, status);

-- 납부 멱등 레코드
-- payment_id 가 UNIQUE 자연키 — 동일 paymentId 재전송을 DB 레벨에서 차단한다(최후 방어).
CREATE TABLE statement_payments (
    id              BIGSERIAL       PRIMARY KEY,
    statement_id    BIGINT          NOT NULL REFERENCES card_statements(id),
    payment_id      VARCHAR(64)     NOT NULL,   -- 멱등 자연키
    amount          NUMERIC(19,2)   NOT NULL,
    paid_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_payment_amount_positive CHECK (amount > 0)
);

-- 멱등 키 — 동일 paymentId 중복 저장 불가(3단 멱등 방어 3층)
CREATE UNIQUE INDEX uq_statement_payment_id ON statement_payments (payment_id);
-- 명세서 단위 납부 이력 조회
CREATE INDEX idx_statement_payments_stmt ON statement_payments (statement_id);

COMMENT ON TABLE card_statements IS '청구서(명세서). billing_year_month 가 카드계정당 자연키. OPEN→CLOSED(마감)→PAID/DELINQUENT.';
COMMENT ON TABLE statement_payments IS '납부 멱등 레코드. payment_id(결제 자연키)가 UNIQUE — 재전송·리플레이 시 중복 납부를 차단하는 최후 방어.';
COMMENT ON COLUMN card_statements.billing_year_month IS '청구주기 YYYY-MM 형식 (예: 2026-08).';
COMMENT ON COLUMN card_statements.due_date IS '납부 만기일. 이 날을 넘기면 연체 배치가 DELINQUENT 로 전이시킨다.';
COMMENT ON COLUMN statement_payments.payment_id IS '결제 자연키 — 재전송 시 중복 납부를 차단하는 유일한 수단. UNIQUE 제약이 최후 차단선.';
