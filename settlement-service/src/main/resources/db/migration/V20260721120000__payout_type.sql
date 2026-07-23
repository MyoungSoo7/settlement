-- V20260721120000: Payout 지급유형(payout_type) 도입 + (정산, 유형) 멱등 유니크 (seed-p0-1)
--
-- 무엇을:
--   1) payouts.payout_type 컬럼 추가(NOT NULL). 즉시지급(IMMEDIATE)/보류해제(HOLDBACK_RELEASE) 구분.
--   2) 멱등 유니크를 (settlement_id) 단일 → (settlement_id, payout_type) 복합 부분 유니크로 교체.
--
-- 왜: 정산 1건은 확정 즉시지급분과 홀드백 해제분이라는 성격이 다른 두 송금을 낳는다. 기존
--     uq_payouts_settlement(settlement_id 단일)은 두 유형의 공존 자체를 막아 홀드백 해제 지급을
--     구조적으로 불가능하게 했다. 유형 축을 더해 "(정산, 유형)당 최대 1건" 으로 멱등을 재정의한다.
--
-- 백필: ADD COLUMN 의 DEFAULT 로 기존 행을 원자적으로 'IMMEDIATE' 로 채운다(별도 DML 없음 —
--   append-only 원장/지급 레코드에 UPDATE 를 발행하지 않는다). DEFAULT 는 유지한다: 유형 미지정
--   레거시 삽입 경로(예: 원시 INSERT 시드)와의 하위 호환을 위해 컬럼 기본값을 IMMEDIATE 로 남긴다.
--
-- 하위 호환: 기존 행은 정산당 payout 이 최대 1건(구 유니크)이었고 전부 IMMEDIATE 로 채워지므로
--   새 복합 유니크를 그대로 만족한다 → 마이그레이션은 데이터 손실·충돌 없이 통과한다.
--
-- 롤백 조건(수동): payout_type 에 'HOLDBACK_RELEASE' 행이 존재하면 롤백 불가(구 단일 유니크가
--   같은 정산의 두 행을 거부). HOLDBACK_RELEASE 가 0건일 때에 한해, 복합 유니크를 드롭하고
--   단일 uq_payouts_settlement 를 재생성한 뒤 payout_type 컬럼을 드롭하는 순서로만 되돌린다.

ALTER TABLE public.payouts
    ADD COLUMN IF NOT EXISTS payout_type varchar(20) NOT NULL DEFAULT 'IMMEDIATE';

-- 구 단일 축 유니크 제거 후 (정산, 유형) 복합 부분 유니크로 교체.
-- 부분(WHERE settlement_id IS NOT NULL)은 구 인덱스와 동일 — 정산 없는 수동 송금(settlement_id NULL)은
-- 유니크 대상에서 제외해 여러 건 공존을 계속 허용한다.
DROP INDEX IF EXISTS public.uq_payouts_settlement;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payouts_settlement_type
    ON public.payouts (settlement_id, payout_type)
    WHERE settlement_id IS NOT NULL;

COMMENT ON COLUMN public.payouts.payout_type IS '지급유형 IMMEDIATE|HOLDBACK_RELEASE — 정산당 유형별 1건.';
COMMENT ON INDEX public.uq_payouts_settlement_type IS '(정산, 지급유형)당 payout 1건 — 이중 지급 차단.';
