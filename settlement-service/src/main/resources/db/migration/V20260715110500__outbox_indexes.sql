-- V20260715110500: outbox 조회 인덱스 보강 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: (1) 미발행(PENDING/FAILED) 폴링용 부분 인덱스, (2) 집합(aggregate) 이력 조회 인덱스.
-- 왜: order-service 는 V28 에서 outbox 폴링을 status IN ('PENDING','FAILED') 부분 인덱스로, 이력 조회를
--     (aggregate_type, aggregate_id) 인덱스로 커버한다. settlement_db(V1 export)는 전(全)상태
--     idx_outbox_status_created(status, created_at) 만 있고 aggregate 인덱스가 없었다 — order 동형 보강.
--
-- 기존 인덱스와의 중복 회피 확인:
--   - V1 idx_outbox_status_created (status, created_at)       : 전상태 대상(부분 아님). 아래 부분 인덱스는
--       PUBLISHED 를 제외해 폴러 스캔 대상을 미발행 행으로만 좁혀 더 작고 빠르다(중복 아닌 보완).
--   - V20260623120000 idx_outbox_pending_claim (created_at, claimed_at) WHERE status='PENDING'
--       : claim 리스 후보 조회용(선두열 created_at, PENDING 만). 아래 부분 인덱스는 선두열 status +
--       FAILED 포함이라 목적·형태가 다르다.
--   - aggregate 인덱스는 V1 에 아예 없다.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON public.outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON public.outbox_events (aggregate_type, aggregate_id);

COMMENT ON INDEX public.idx_outbox_unpublished IS
    '미발행(PENDING/FAILED) 폴링 최적화 — PUBLISHED 제외 부분 인덱스로 폴러 스캔 대상 축소.';
COMMENT ON INDEX public.idx_outbox_aggregate IS
    '특정 집합(aggregate_type, aggregate_id)의 이벤트 이력 조회.';
