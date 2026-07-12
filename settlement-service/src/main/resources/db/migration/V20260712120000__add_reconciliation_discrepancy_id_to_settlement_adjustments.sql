-- PG 대사 승인 → 역정산(clawback) 루프 마감 (docs/design 참조).
-- settlement_adjustments 는 지금까지 refund_id / chargeback_id 두 출처만 가졌다(둘 중 하나).
-- 대사 조정은 refund_id·chargeback_id 둘 다 NULL 이므로 전용 FK 컬럼을 추가하고,
-- 출처 제약을 "정확히 하나만 non-null" 로 넓힌다.

ALTER TABLE public.settlement_adjustments
    ADD COLUMN IF NOT EXISTS reconciliation_discrepancy_id bigint;

-- 과거 xor 제약(refund_id XOR chargeback_id)이 존재했다면 제거.
-- (consolidated V1 baseline 에는 없을 수 있어 IF EXISTS 로 안전하게 no-op 허용)
ALTER TABLE public.settlement_adjustments
    DROP CONSTRAINT IF EXISTS chk_adjustment_refund_xor_chargeback;

-- 한 조정 row 는 refund_id / chargeback_id / reconciliation_discrepancy_id 중 최대 하나만 가진다.
-- (한 조정이 두 출처를 동시에 가리키면 이중 링크·이중 계상 위험 → 그것을 금지한다)
--
-- ★ "정확히 하나(=1)" 가 아니라 "최대 하나(<=1)" 인 이유:
--   현 baseline(V1)에는 refund/chargeback XOR 제약이 애초에 없었고, 기존 환불 경로
--   (AdjustSettlementForRefundService 의 refundId=null 2-arg 오버로드 / refundId 없는 이벤트)가
--   출처 FK 가 모두 NULL 인 조정 row 를 정상적으로 생성한다. "정확히 하나" 로 강제하면 그 환불
--   조정 insert 가 런타임에 실패해 환불 미반영(자금 사고)이 된다. 따라서 다중 출처만 금지하는
--   "최대 하나" 로 무결성을 확보하고 legacy 무출처 row 는 계속 허용한다.
ALTER TABLE public.settlement_adjustments
    DROP CONSTRAINT IF EXISTS chk_adjustment_source_at_most_one;
ALTER TABLE public.settlement_adjustments
    ADD CONSTRAINT chk_adjustment_source_at_most_one CHECK (
        (CASE WHEN refund_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN chargeback_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN reconciliation_discrepancy_id IS NOT NULL THEN 1 ELSE 0 END)
      <= 1
    );

CREATE INDEX IF NOT EXISTS idx_adjustments_reconciliation_discrepancy_id
    ON public.settlement_adjustments (reconciliation_discrepancy_id);
