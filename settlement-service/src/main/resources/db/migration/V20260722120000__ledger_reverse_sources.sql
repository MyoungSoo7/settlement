-- V20260722120000: 원장 역분개 출처 확장 — 차지백·PG 대사도 REFUND 처럼 역분개 (seed-p0-2)
--
-- 무엇을: 3개 CHECK 제약의 허용값을 넓혀 차지백·PG 대사 출처의 역분개 분개/작업을 허용한다.
--   1) chk_ledger_ref_type       + 'CHARGEBACK','PG_RECONCILIATION'  (ledger_entries.reference_type)
--   2) chk_ledger_entry_type     + 'CHARGEBACK_REVERSED','RECON_REVERSED' (ledger_entries.entry_type)
--   3) chk_ledger_outbox_task_type + 'REVERSE_CHARGEBACK','REVERSE_RECONCILIATION' (ledger_outbox.task_type)
--
-- 왜: 지금까지 원장 역분개는 REFUND 출처만 허용됐다. 차지백 ACCEPT·PG 대사 clawback 은 정산금 조정
--     (settlement_adjustments)만 남기고 원장을 안 건드려 (조정 ↔ 역분개) 1:1 대칭이 깨졌다.
--     환불과 동일한 균형 역분개를 걸려면 위 값들이 CHECK 를 통과해야 한다.
--
-- 멱등/중복차단: 신규 출처의 이중 적재는 기존 uq_ledger_reference_accounts
--   (reference_id, reference_type, debit_account, credit_account) 가 그대로 막는다 —
--   reference_type 이 축에 포함돼 CHARGEBACK/PG_RECONCILIATION 는 REFUND 와 자동으로 분리된다.
--
-- 안전성: CHECK 를 넓히는 방향이라 기존 행(SETTLEMENT/REFUND/... 만 존재)은 새 제약을 즉시 만족한다.
--   DROP → ADD(NOT VALID) → VALIDATE 순서로 재정의한다(원 제약과 동일 패턴). 빈 DB·기존 DB 모두 통과.
--
-- 롤백 조건(수동): reference_type 에 'CHARGEBACK'/'PG_RECONCILIATION', entry_type 에
--   'CHARGEBACK_REVERSED'/'RECON_REVERSED', task_type 에 'REVERSE_CHARGEBACK'/'REVERSE_RECONCILIATION'
--   행이 하나도 없을 때에 한해, 각 CHECK 를 구(舊) 허용값 집합으로 되돌릴 수 있다. 해당 값의 행이
--   존재하면 롤백 시 VALIDATE 가 실패하므로 되돌리지 않는다.

ALTER TABLE public.ledger_entries DROP CONSTRAINT IF EXISTS chk_ledger_ref_type;
ALTER TABLE public.ledger_entries
    ADD CONSTRAINT chk_ledger_ref_type
        CHECK (reference_type IN ('SETTLEMENT','REFUND','CHARGEBACK','PG_RECONCILIATION')) NOT VALID;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_ref_type;

ALTER TABLE public.ledger_entries DROP CONSTRAINT IF EXISTS chk_ledger_entry_type;
ALTER TABLE public.ledger_entries
    ADD CONSTRAINT chk_ledger_entry_type
        CHECK (entry_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','REFUND_REVERSED',
                              'CHARGEBACK_REVERSED','RECON_REVERSED',
                              'COMMISSION_RECOGNIZED','PAYOUT_EXECUTED')) NOT VALID;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_entry_type;

ALTER TABLE public.ledger_outbox DROP CONSTRAINT IF EXISTS chk_ledger_outbox_task_type;
ALTER TABLE public.ledger_outbox
    ADD CONSTRAINT chk_ledger_outbox_task_type
        CHECK (task_type IN ('CREATE_ENTRY','REVERSE_ENTRY',
                             'REVERSE_CHARGEBACK','REVERSE_RECONCILIATION')) NOT VALID;
ALTER TABLE public.ledger_outbox VALIDATE CONSTRAINT chk_ledger_outbox_task_type;
