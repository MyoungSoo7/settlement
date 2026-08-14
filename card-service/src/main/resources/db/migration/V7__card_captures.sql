-- V7: 카드 매입·환불 (Phase 2 AC2)
--
-- card_captures: 승인(authorization_holds)에 대한 매입 이벤트 — 하나의 승인에 여러 부분매입 가능.
--   capture_id 가 자연키(멱등 키). lemuel.card.captured 이벤트의 captureId 와 1:1.
-- authorization_holds: REFUNDED 상태 추가(환불 후 한도 원복).

CREATE TABLE card_captures (
    id                BIGSERIAL       PRIMARY KEY,
    capture_id        VARCHAR(64)     NOT NULL,                    -- 자연키, 멱등 키 (VAN 매입번호)
    authorization_id  VARCHAR(64)     NOT NULL,                    -- 승인 홀드 FK (authorization_holds.authorization_id)
    card_id           BIGINT          NOT NULL REFERENCES cards(id),
    card_account_id   BIGINT          NOT NULL REFERENCES card_accounts(id),
    holder_user_id    BIGINT          NOT NULL,
    captured_amount   NUMERIC(19,2)   NOT NULL,
    merchant_name     VARCHAR(200),
    captured_at       TIMESTAMPTZ     NOT NULL,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_capture_amount_positive CHECK (captured_amount > 0)
);

-- 멱등 키 — 동일 captureId 중복 저장 불가
CREATE UNIQUE INDEX uq_card_capture_id ON card_captures (capture_id);

-- 승인 기준 매입 이력 조회 (부분매입 총액 계산)
CREATE INDEX idx_card_capture_auth_id ON card_captures (authorization_id);

-- authorization_holds: REFUNDED 상태 지원
-- V6 CHECK 제약을 확장한다(DROP → ADD).
ALTER TABLE authorization_holds
    DROP CONSTRAINT chk_hold_status;
ALTER TABLE authorization_holds
    ADD CONSTRAINT chk_hold_status
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'PARTIALLY_CAPTURED', 'VOIDED', 'EXPIRED', 'REFUNDED'));

COMMENT ON TABLE card_captures IS '카드 매입 이벤트. capture_id(VAN 매입번호)가 자연키·멱등 키. 하나의 승인에 여러 부분매입 가능.';
COMMENT ON COLUMN card_captures.capture_id IS 'VAN 네트워크 매입번호 — 재전송 시 중복 매입을 차단하는 유일한 수단. UNIQUE 제약이 최후 차단선.';
COMMENT ON COLUMN card_captures.authorization_id IS '매입 대상 승인번호 (authorization_holds.authorization_id 참조). 승인 없는 매입은 존재하지 않는다.';
