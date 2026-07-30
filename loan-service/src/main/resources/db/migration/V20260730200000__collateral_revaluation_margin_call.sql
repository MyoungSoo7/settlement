-- V20260730200000: 담보 재평가 이력 + 마진콜 (Phase 2)
--
-- collateral_revaluations : 재평가 이력. collaterals.appraised_value 를 UPDATE 하지 않고 행을 쌓는다 —
--   설정 시점 평가액은 그 대출의 한도 산정 근거이므로 덮어쓰면 "왜 그때 그 금액을 승인했나"를
--   사후에 재현할 수 없다. 마진콜 판정만 최신 이력을 본다.
-- margin_calls : 마진콜 발생·해소 이력. 대출 1건에 여러 번 발생 가능(시가가 오르내리며 반복)하고
--   각 발생이 독립적으로 해소(RESOLVED)되거나 강제처분으로 이관(ESCALATED)된다.

CREATE TABLE IF NOT EXISTS collateral_revaluations (
    id             BIGSERIAL      PRIMARY KEY,
    collateral_id  BIGINT         NOT NULL REFERENCES collaterals (id),
    revalued_value NUMERIC(19, 2) NOT NULL,
    source         VARCHAR(40)    NOT NULL,   -- MARKET_SERVICE / COMMON_DATA_SERVICE / MANUAL
    revalued_at    TIMESTAMP      NOT NULL,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_collateral_reval_value CHECK (revalued_value > 0)
);

-- 최신 재평가 1건 조회가 주 경로 — (담보, 평가시각 DESC) 커버링.
CREATE INDEX IF NOT EXISTS idx_collateral_reval_latest
    ON collateral_revaluations (collateral_id, revalued_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS margin_calls (
    id              BIGSERIAL      PRIMARY KEY,
    loan_id         BIGINT         NOT NULL REFERENCES secured_loans (id),
    collateral_id   BIGINT         NOT NULL REFERENCES collaterals (id),
    required_amount NUMERIC(19, 2) NOT NULL,   -- 발생 시점 부족액 스냅샷
    status          VARCHAR(20)    NOT NULL,   -- OPEN/RESOLVED/ESCALATED
    opened_at       TIMESTAMP      NOT NULL,
    closed_at       TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_margin_call_required CHECK (required_amount > 0),
    CONSTRAINT chk_margin_call_status CHECK (status IN ('OPEN', 'RESOLVED', 'ESCALATED')),
    -- 종료 상태는 종료 시각이 있어야 하고, OPEN 은 없어야 한다(도메인과 이중 방어).
    CONSTRAINT chk_margin_call_closed_at CHECK (
        (status = 'OPEN' AND closed_at IS NULL)
     OR (status <> 'OPEN' AND closed_at IS NOT NULL AND closed_at >= opened_at)
    )
);

-- ★ 대출당 활성(OPEN) 마진콜은 1건만. 판정 배치가 재실행되거나 동시 실행돼도 같은 부족 상황으로
--   마진콜이 중복 발생하지 않게 DB 에서 막는다 — 애플리케이션 선체크만으로는 TOCTOU 가 남는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_margin_call_open_per_loan
    ON margin_calls (loan_id)
    WHERE status = 'OPEN';

COMMENT ON INDEX uq_margin_call_open_per_loan IS
    '대출당 활성 마진콜 유일성 — 판정 배치 재실행·동시 실행 시 중복 발생 차단(TOCTOU 최종선).';
