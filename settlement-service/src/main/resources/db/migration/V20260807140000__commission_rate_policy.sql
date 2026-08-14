-- 수수료율 유효기간 정책 (ADR 0032).
--
-- 요율을 enum 상수가 아니라 기간을 가진 데이터로 다룬다 — "9월부터 2.3%", "이 셀러는 계약상 1.8%",
-- "한시 인하"를 배포 없이 표현하기 위함이다. 표가 비어 있으면 도입 전과 100% 동일하게 동작한다
-- (해석이 등급 enum 기본율로 폴백 — 무행동 착지).

-- EXCLUDE 제약에 equality 열(scope, scope_key)을 함께 넣으려면 필요하다.
-- postgres:17-alpine 에 btree_gist 1.7 번들 확인(2026-08-07, ADR 0032 §4-1).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE IF NOT EXISTS commission_rate_policy (
    id             BIGSERIAL    PRIMARY KEY,
    scope          VARCHAR(16)  NOT NULL,
    scope_key      VARCHAR(64)  NOT NULL,      -- SELLER:'12345' | TIER:'VIP'
    rate           NUMERIC(6,5) NOT NULL,      -- 0.02500 = 2.5%. 금액 정책이라 부동소수 금지
    effective_from DATE         NOT NULL,
    effective_to   DATE,                       -- NULL = 무기한
    reason         VARCHAR(255) NOT NULL,      -- 왜 이 요율인가 — 감사 없이 요율이 바뀌지 않게 필수
    created_by     VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ,                -- 조기 종료 시각(행 UPDATE 대신 close + 신규 행)

    CONSTRAINT chk_crp_scope CHECK (scope IN ('SELLER', 'TIER')),
    CONSTRAINT chk_crp_rate  CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT chk_crp_range CHECK (effective_to IS NULL OR effective_to > effective_from),

    -- 같은 (scope, scope_key) 안에서 기간이 겹치면 어느 요율이 맞는지 설명할 수 없다.
    -- 우선순위로 푸는 대신 입력 시점에 DB 가 막는다. [from, to) 반열림이라 경계 접촉은 중첩이 아니다.
    CONSTRAINT ex_crp_no_overlap EXCLUDE USING gist (
        scope     WITH =,
        scope_key WITH =,
        daterange(effective_from, COALESCE(effective_to, DATE '9999-12-31'), '[)') WITH &&
    )
);

COMMENT ON TABLE commission_rate_policy IS
    '수수료율 유효기간 정책 (ADR 0032) — 행 UPDATE 금지, 변경은 close + 신규 행';
COMMENT ON COLUMN commission_rate_policy.reason IS '요율 근거 — 감사 추적용 필수 입력';

-- 적용 요율의 근거를 정산에 영구 보존한다. 셀러가 "왜 3.5% 인가"를 물었을 때 답하려면
-- 요율 값만으로는 부족하다(같은 값이 셀러 계약에서 왔는지 등급 기본값인지 구분되지 않는다).
ALTER TABLE settlements
    ADD COLUMN IF NOT EXISTS commission_rate_source VARCHAR(32);

COMMENT ON COLUMN settlements.commission_rate_source IS
    '적용 요율의 근거 — SELLER:{id} | TIER:{등급} | DEFAULT_TIER. NULL 은 정책 도입 전 행(DEFAULT_TIER 로 해석)';
