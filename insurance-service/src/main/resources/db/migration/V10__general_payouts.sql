-- ─────────────────────────────────────────────────────────────────────
-- V10: 일반지급 (§14 — 해약환급금·만기보험금·철회환급금)
--
-- 일반지급은 청구 접수가 아니라 Policy terminal 전이(해지·만기·철회·실효소멸)가
-- 낳는다 (D-G1). 행은 산출근거 스냅샷(D-G5: 기납입합계·적용요율·경과월수·납입회차수)을
-- 고정 보존한다 — 지급액이 어떻게 나왔는지 행 하나로 재구성 가능해야 한다.
--
-- 멱등:
--   L1: payout_id UUID UNIQUE
--   L3: UNIQUE(policy_id, payout_type) — terminal 상태 상호배타 → 계약당 유형별 1건이 자연키
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE general_payouts (
    id                  BIGSERIAL     PRIMARY KEY,
    payout_id           UUID          NOT NULL,
    policy_id           UUID          NOT NULL,   -- insurance_policies.policy_id
    policy_number       VARCHAR(64)   NOT NULL,   -- 증권번호 — 이벤트 파티션 키

    -- D-G1: 지급 유형 3종
    payout_type         VARCHAR(32)   NOT NULL,

    -- 금액 (BigDecimal — double/float 금지). 0원 지급 행은 존재하지 않는다 (D-G3)
    amount              NUMERIC(19,2) NOT NULL,

    -- D-G5: 산출근거 스냅샷 — 정상납입 가정 기납입합계 × 적용요율 (D-G2·D-G3)
    paid_premium_total  NUMERIC(19,2) NOT NULL,
    applied_rate        NUMERIC(5,4)  NOT NULL,
    elapsed_months      BIGINT        NOT NULL,
    installment_count   INTEGER       NOT NULL,

    -- D-G4: 상태머신 — REQUESTED → PAID 전이 1개만 (InvalidGeneralPayoutTransitionException)
    status              VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',
    requested_on        DATE          NOT NULL,   -- 지급 요청일 (전이 발생일, KST)
    paid_on             DATE,                     -- 지급일

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_general_payout_id          UNIQUE (payout_id),
    CONSTRAINT uq_general_payout_policy_type UNIQUE (policy_id, payout_type),

    CONSTRAINT chk_general_payout_type
        CHECK (payout_type IN ('SURRENDER_REFUND', 'MATURITY_BENEFIT', 'WITHDRAWAL_REFUND')),
    CONSTRAINT chk_general_payout_status
        CHECK (status IN ('REQUESTED', 'PAID')),
    CONSTRAINT chk_general_payout_amount
        CHECK (amount > 0),
    -- 지급 완료 ↔ 지급일 존재 — 반쪽 지급 행 금지
    CONSTRAINT chk_general_payout_paid_on
        CHECK ((status = 'PAID') = (paid_on IS NOT NULL))
);

-- 일반지급 실행 배치 스캔 — REQUESTED 전건 (LoadGeneralPayoutPort.findRequested)
CREATE INDEX idx_general_payout_requested
    ON general_payouts (requested_on)
    WHERE status = 'REQUESTED';

-- 계약별 내역 조회 (GET /api/insurance/policies/{policyNumber}/payouts)
CREATE INDEX idx_general_payout_policy
    ON general_payouts (policy_id);

COMMENT ON TABLE general_payouts IS
    '일반지급 (§14) — Policy terminal 전이가 낳는 계약자 앞 지급. 산출근거 스냅샷 고정 보존';
