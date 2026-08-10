-- V6: 월 수수료 마감 스냅샷 (commission_closings)
--
-- 월 마감 배치가 FC별 당월 지급 실적(paid_at 기준)을 확정 스냅샷으로 남긴다.
-- 재무 마감 스냅샷이므로 append-only — 재마감·정정으로 과거 스냅샷을 덮어쓰지 않는다
-- (servicing_changes 와 동일한 트리거 강제).
CREATE TABLE commission_closings (
    id                 BIGSERIAL     PRIMARY KEY,
    closing_id         UUID          NOT NULL,
    fc_id              VARCHAR(64)   NOT NULL,
    closing_month      DATE          NOT NULL,   -- 월 1일로 정규화 (예: 2026-07-01)
    total_paid_amount  NUMERIC(19,2) NOT NULL,   -- 당월 지급 합계 (paid_at 기준, 이후 환수 전이와 무관)
    installment_count  INTEGER       NOT NULL,   -- 당월 지급 회차 수
    closed_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_closing_amount_non_negative CHECK (total_paid_amount >= 0),
    CONSTRAINT chk_closing_count_non_negative CHECK (installment_count >= 0),
    -- 월 1일 정규화 강제 — "2026-07-15 마감" 같은 비정규 행 차단
    CONSTRAINT chk_closing_month_normalized CHECK (closing_month = DATE_TRUNC('month', closing_month)::DATE)
);

CREATE UNIQUE INDEX uq_commission_closing_id ON commission_closings (closing_id);
-- 멱등성 L3 — (fc, 월) 마감은 1회만. 배치 재실행 시 이미 마감된 FC 는 스킵된다.
CREATE UNIQUE INDEX uq_commission_closing_fc_month ON commission_closings (fc_id, closing_month);
CREATE INDEX idx_commission_closing_month ON commission_closings (closing_month);

-- append-only 강제 (UPDATE·DELETE 거부) — 마감 스냅샷 변조 차단
CREATE OR REPLACE FUNCTION commission_closings_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'commission_closings 는 append-only 입니다: % 연산 불가 (마감 스냅샷 변조 차단)', TG_OP;
END;
$$;
CREATE TRIGGER trg_commission_closings_append_only
    BEFORE UPDATE OR DELETE ON commission_closings
    FOR EACH ROW EXECUTE FUNCTION commission_closings_block_modify();

COMMENT ON TABLE commission_closings IS '월 수수료 마감 스냅샷 — FC별 당월 지급 실적(paid_at 기준). append-only, UNIQUE(fc_id, closing_month) 멱등.';
