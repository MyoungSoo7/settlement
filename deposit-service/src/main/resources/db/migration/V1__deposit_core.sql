-- V1: deposit-service 자체 DB(lemuel_deposit) — 예치금 코어
--
-- 셀러 예치 계좌(deposit_accounts), append-only 원장(deposit_entries),
-- 선점(deposit_holds), 상계 부족분(deposit_offset_shortfalls) 4개 테이블.
--
-- 잔고 불변식(total = available + locked, 3필드 모두 >= 0)은
-- SellerDepositAccount.enforceInvariant() 가 애플리케이션 레벨 1차 방어선이고,
-- 아래 CHECK 제약이 최후 방어선이다. 원장의 자연키 UNIQUE 는 Triple Idempotency L3.

-- ── deposit_accounts ─────────────────────────────────────────────────────────
CREATE TABLE deposit_accounts (
    id           BIGSERIAL      PRIMARY KEY,
    seller_id    BIGINT         NOT NULL,
    available    NUMERIC(19,2)  NOT NULL DEFAULT 0,
    locked       NUMERIC(19,2)  NOT NULL DEFAULT 0,
    total        NUMERIC(19,2)  NOT NULL DEFAULT 0,
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_deposit_accounts_seller_id UNIQUE (seller_id),
    -- 잔고 불변식 최후 방어선.
    CONSTRAINT chk_deposit_accounts_available_non_negative CHECK (available >= 0),
    CONSTRAINT chk_deposit_accounts_locked_non_negative CHECK (locked >= 0),
    CONSTRAINT chk_deposit_accounts_total_non_negative CHECK (total >= 0),
    CONSTRAINT chk_deposit_accounts_total_eq_sum CHECK (total = available + locked)
);

COMMENT ON TABLE deposit_accounts IS
    '셀러 예치 계좌. 잔고 불변식(total=available+locked, 3필드 모두 >=0)은 SellerDepositAccount 가 1차, DB CHECK 가 최후 방어선.';

-- ── deposit_holds ────────────────────────────────────────────────────────────
-- (holder_type, holder_reference) 가 자연키이자 멱등 키 — 동일 키 재요청 시 기존 hold 반환.
CREATE TABLE deposit_holds (
    id                BIGSERIAL      PRIMARY KEY,
    account_id        BIGINT         NOT NULL REFERENCES deposit_accounts(id),
    holder_type       VARCHAR(30)    NOT NULL,
    holder_reference  VARCHAR(100)   NOT NULL,
    original_amount   NUMERIC(19,2)  NOT NULL,
    remaining_amount  NUMERIC(19,2)  NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    expires_at        TIMESTAMP,
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_deposit_holds_holder UNIQUE (holder_type, holder_reference),
    CONSTRAINT chk_deposit_holds_holder_type
        CHECK (holder_type IN ('CARD_AUTHORIZATION', 'LOAN_DISBURSEMENT', 'INVESTMENT_EXECUTION', 'MANUAL')),
    CONSTRAINT chk_deposit_holds_status
        CHECK (status IN ('ACTIVE', 'PARTIALLY_CAPTURED', 'CAPTURED', 'EXPIRED', 'VOIDED', 'RELEASED')),
    CONSTRAINT chk_deposit_holds_original_amount_positive CHECK (original_amount > 0),
    CONSTRAINT chk_deposit_holds_remaining_amount_non_negative CHECK (remaining_amount >= 0),
    CONSTRAINT chk_deposit_holds_remaining_le_original CHECK (remaining_amount <= original_amount)
);

-- FK(account_id) 조회 지원 — 계좌별 활성 hold 조회.
CREATE INDEX idx_deposit_holds_account ON deposit_holds (account_id);
-- SpringDataDepositHoldRepository.findByStatusAndExpiresAtBefore 는 항상 status=ACTIVE 로만 호출된다
-- (DepositHoldPersistenceAdapter.findActiveExpiredBefore) — 만료 배치 스캔용 부분 인덱스.
CREATE INDEX idx_deposit_holds_active_expiring ON deposit_holds (expires_at) WHERE status = 'ACTIVE';

COMMENT ON TABLE deposit_holds IS
    '예치금 선점(hold). (holder_type, holder_reference) 자연키가 멱등 키(uq_deposit_holds_holder). 상태머신: ACTIVE→PARTIALLY_CAPTURED→CAPTURED, ACTIVE/PARTIALLY_CAPTURED→EXPIRED|VOIDED|RELEASED.';

-- ── deposit_entries (append-only) ───────────────────────────────────────────
-- 모든 잔고 변경은 이 테이블에 엔트리 한 건 이상으로 기록되어야 한다(대사 가능성).
CREATE TABLE deposit_entries (
    id               BIGSERIAL      PRIMARY KEY,
    account_id       BIGINT         NOT NULL REFERENCES deposit_accounts(id),
    entry_type       VARCHAR(20)    NOT NULL,
    amount           NUMERIC(19,2)  NOT NULL,
    reference_id     VARCHAR(100),
    reference_type   VARCHAR(50),
    offset_sequence  INTEGER        NOT NULL DEFAULT 0,
    source_hold_id   BIGINT         REFERENCES deposit_holds(id),
    created_at       TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_deposit_entries_type
        CHECK (entry_type IN ('CREDIT', 'DEBIT', 'HOLD', 'RELEASE', 'OFFSET')),
    CONSTRAINT chk_deposit_entries_amount_positive CHECK (amount > 0),
    -- L3 멱등 방어선(Triple Idempotency) — 동일 참조에 대한 중복 원장 기록을 DB 레벨에서 차단.
    CONSTRAINT uq_deposit_entries_natural
        UNIQUE (account_id, entry_type, reference_type, reference_id, offset_sequence)
);

-- 계좌별 원장 조회(대사·명세서) 지원.
CREATE INDEX idx_deposit_entries_account_created ON deposit_entries (account_id, created_at);
-- source_hold_id FK 조회 지원(hold 캡처/상계 이력 역추적). NULL(hold 없는 늦은 청구)은 색인 대상 제외.
CREATE INDEX idx_deposit_entries_source_hold ON deposit_entries (source_hold_id) WHERE source_hold_id IS NOT NULL;

COMMENT ON TABLE deposit_entries IS
    'append-only 예치금 원장. UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence) 가 L3 멱등 방어선(Triple Idempotency).';

-- ── deposit_offset_shortfalls ────────────────────────────────────────────────
-- applyOffset 시 가용 재원이 요청액에 못 미칠 때의 부족분. OPEN → RESOLVED | WRITTEN_OFF.
CREATE TABLE deposit_offset_shortfalls (
    id                BIGSERIAL      PRIMARY KEY,
    seller_id         BIGINT         NOT NULL REFERENCES deposit_accounts(seller_id),
    holder_type       VARCHAR(30)    NOT NULL,
    holder_reference  VARCHAR(100)   NOT NULL,
    requested_amount  NUMERIC(19,2)  NOT NULL,
    applied_amount    NUMERIC(19,2)  NOT NULL,
    shortfall_amount  NUMERIC(19,2)  NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    source_hold_id    BIGINT         REFERENCES deposit_holds(id),
    occurred_at       TIMESTAMPTZ    NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_deposit_offset_shortfalls_holder_type
        CHECK (holder_type IN ('CARD_AUTHORIZATION', 'LOAN_DISBURSEMENT', 'INVESTMENT_EXECUTION', 'MANUAL')),
    CONSTRAINT chk_deposit_offset_shortfalls_status
        CHECK (status IN ('OPEN', 'RESOLVED', 'WRITTEN_OFF')),
    CONSTRAINT chk_deposit_offset_shortfalls_requested_positive CHECK (requested_amount > 0),
    CONSTRAINT chk_deposit_offset_shortfalls_applied_non_negative CHECK (applied_amount >= 0),
    CONSTRAINT chk_deposit_offset_shortfalls_shortfall_non_negative CHECK (shortfall_amount >= 0)
);

-- DepositShortfallRetryScheduler 의 findByStatus(OPEN) 재상계 배치 조회용.
CREATE INDEX idx_deposit_offset_shortfalls_status ON deposit_offset_shortfalls (status);
CREATE INDEX idx_deposit_offset_shortfalls_seller ON deposit_offset_shortfalls (seller_id);

COMMENT ON TABLE deposit_offset_shortfalls IS
    '상계 부족분. DepositShortfallRetryScheduler 가 OPEN 상태를 주기적으로 재상계 시도한다.';

-- ── ROLLBACK NOTES ───────────────────────────────────────────────────────────
-- DROP TABLE deposit_offset_shortfalls;
-- DROP TABLE deposit_entries;
-- DROP TABLE deposit_holds;
-- DROP TABLE deposit_accounts;
-- (생성 역순 — FK 의존성 때문에 자식 테이블부터 제거해야 한다.)
