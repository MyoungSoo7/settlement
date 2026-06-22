-- V20260623120000: settlement outbox 멀티워커 발행을 위한 claim(리스) 컬럼
--
-- 목적: shared-common 의 outbox 폴러(SpringDataOutboxEventRepositoryCustomImpl)가
-- SELECT ... FOR UPDATE SKIP LOCKED 로 PENDING 행을 claim 하고 claimed_at/claimed_by 로
-- 리스를 표시한다. order(opslab)·loan 베이스라인엔 이 컬럼이 있으나 settlement(public)
-- 베이스라인(V1)엔 누락돼, settlement 가 SettlementCreated/Confirmed 발행 시
-- "column does not exist" 로 폴링이 전량 실패하던 문제를 해소한다.
-- settlement_db 는 public 스키마를 사용한다(order 의 opslab 와 구분).

ALTER TABLE public.outbox_events
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(64);

-- 클레임 후보 조회 최적화: PENDING 행을 created_at 순으로, claimed_at 필터와 함께.
CREATE INDEX IF NOT EXISTS idx_outbox_pending_claim
    ON public.outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';
