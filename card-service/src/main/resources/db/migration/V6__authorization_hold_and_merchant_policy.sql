-- V6: 카드 실시간 승인(Phase 2 AC1)
--
-- authorization_holds: 승인 홀드. authorization_id 가 자연키(멱등 키).
-- merchant_policies: 카드계정·카드 단위 지출정책(MCC 허용/차단, 1회·일·월 한도, 해외·온라인 토글).

CREATE TABLE authorization_holds (
    id                  BIGSERIAL       PRIMARY KEY,
    authorization_id    VARCHAR(64)     NOT NULL,      -- 자연키, 멱등 키 (VAN 승인번호)
    card_id             BIGINT          NOT NULL REFERENCES cards(id),
    card_account_id     BIGINT          NOT NULL REFERENCES card_accounts(id),
    holder_user_id      BIGINT          NOT NULL,
    amount              NUMERIC(19,2)   NOT NULL,
    status              VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    merchant_name       VARCHAR(200),
    mcc                 VARCHAR(4),                    -- 4자리 가맹점 업종코드
    authorized_at       TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_hold_status
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'PARTIALLY_CAPTURED', 'VOIDED', 'EXPIRED')),
    CONSTRAINT chk_hold_amount_positive CHECK (amount > 0)
);

-- 멱등 키 — 같은 authorizationId 는 중복 저장 불가
CREATE UNIQUE INDEX uq_authorization_hold_auth_id ON authorization_holds (authorization_id);

-- 가용한도 계산 핫패스: card_account_id 기준 ACTIVE 홀드 합계
CREATE INDEX idx_hold_account_status ON authorization_holds (card_account_id, status);

-- card 단위 ACTIVE 홀드 합계 + 일·월 지출 집계
CREATE INDEX idx_hold_card_status ON authorization_holds (card_id, status, authorized_at);

-- ──────────────────────────────────────────────────────────────────────────────

-- merchant_policies: 카드계정 또는 카드 단위 지출정책.
-- card_id IS NULL 이면 계정 단위 정책, NOT NULL 이면 카드 단위 정책.
-- blocked_mccs / allowed_mccs 는 쉼표 구분 MCC 문자열(간소화 저장).
-- 정책이 없으면 "모두 허용" — 미설정은 차단이 아니라 개방이 기본값이다.

CREATE TABLE merchant_policies (
    id                          BIGSERIAL       PRIMARY KEY,
    card_account_id             BIGINT          NOT NULL REFERENCES card_accounts(id),
    card_id                     BIGINT          REFERENCES cards(id),  -- null = 계정 단위
    blocked_mccs                TEXT,           -- 쉼표 구분 MCC 목록 (예: "5812,5813")
    allowed_mccs                TEXT,           -- 쉼표 구분 MCC 목록 (비면 전체 허용)
    max_per_transaction_amount  NUMERIC(19,2),  -- null = 무제한
    daily_spend_limit           NUMERIC(19,2),  -- null = 무제한
    monthly_spend_limit         NUMERIC(19,2),  -- null = 무제한
    overseas_enabled            BOOLEAN         NOT NULL DEFAULT TRUE,
    online_enabled              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 카드계정당 계정 단위 정책은 1개
CREATE UNIQUE INDEX uq_merchant_policy_account
    ON merchant_policies (card_account_id)
    WHERE card_id IS NULL;

-- 카드당 카드 단위 정책은 1개
CREATE UNIQUE INDEX uq_merchant_policy_card
    ON merchant_policies (card_id)
    WHERE card_id IS NOT NULL;

-- 카드 단위 정책 조회
CREATE INDEX idx_merchant_policy_card ON merchant_policies (card_id);
-- 계정 단위 정책 조회
CREATE INDEX idx_merchant_policy_account ON merchant_policies (card_account_id);

COMMENT ON TABLE authorization_holds IS '카드 승인 홀드. authorization_id(VAN 승인번호)가 자연키·멱등 키. 가용한도 = masterLimit - Σ ACTIVE 홀드.';
COMMENT ON TABLE merchant_policies IS '가맹점/MCC 지출정책. card_id IS NULL 이면 계정 단위, NOT NULL 이면 카드 단위. 미설정 = 전체 허용.';
COMMENT ON COLUMN authorization_holds.authorization_id IS 'VAN 네트워크 승인번호 — 재전송·리플레이 시 중복 승인을 구분하는 유일한 수단. UNIQUE 제약이 최후 차단선.';
COMMENT ON COLUMN authorization_holds.mcc IS '4자리 가맹점 업종코드(ISO 18245). null 허용 — 코드 없는 가맹점도 존재한다.';
