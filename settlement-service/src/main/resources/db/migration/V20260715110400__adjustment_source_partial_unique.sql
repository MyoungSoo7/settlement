-- V20260715110400: 조정 출처 1:1 부분 유니크 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: settlement_adjustments 의 refund_id / chargeback_id / reconciliation_discrepancy_id 각각에
--         WHERE ... IS NOT NULL 부분 유니크 인덱스.
-- 왜: 한 환불·한 분쟁·한 대사 불일치는 조정(역정산) row 를 최대 1건만 만들어야 한다. 재시도·중복 소비가
--     같은 출처로 조정을 2건 만들면 이중 차감(자금 사고)이 된다. V20260712120000 의
--     chk_adjustment_source_at_most_one 은 "한 row 가 여러 출처를 가리키는 것"만 막고, "한 출처가 여러
--     row 로 복제되는 것"은 막지 못한다 — 이 부분 유니크가 그 축을 보완한다.
--
-- 부분(WHERE NOT NULL) 인 이유: 무출처 조정(refund/chargeback/recon 전부 NULL — 레거시 환불 경로가
--   정상 생성)은 유니크 대상에서 빠져야 여러 건 공존할 수 있다. NULL 은 부분 인덱스가 제외한다.
--
-- 안전성: UNIQUE INDEX 라 생성 시 즉시 검사(NOT VALID 불가). 빈 DB 는 즉시. 기존 DB 에 같은 출처의
--   중복 조정이 있다면 멱등 결함이므로 생성 실패로 조기 노출된다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_adjustments_refund_id
    ON public.settlement_adjustments (refund_id)
    WHERE refund_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_adjustments_chargeback_id
    ON public.settlement_adjustments (chargeback_id)
    WHERE chargeback_id IS NOT NULL;

-- 대사 clawback 도 동일 근거로 1:1 (task 명시 항목은 refund/chargeback 이나 동일 규칙을 대칭 적용).
CREATE UNIQUE INDEX IF NOT EXISTS uq_adjustments_recon_discrepancy_id
    ON public.settlement_adjustments (reconciliation_discrepancy_id)
    WHERE reconciliation_discrepancy_id IS NOT NULL;

COMMENT ON INDEX public.uq_adjustments_refund_id IS '환불 1건당 조정 1건 — 중복 역정산(이중 차감) 차단.';
COMMENT ON INDEX public.uq_adjustments_chargeback_id IS '분쟁 1건당 조정 1건 — 중복 역정산 차단.';
COMMENT ON INDEX public.uq_adjustments_recon_discrepancy_id IS '대사 불일치 1건당 clawback 조정 1건 — 중복 차단.';
