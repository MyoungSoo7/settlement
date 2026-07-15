-- V20260715110100: settlement_db 값 무결성(CHECK) 복원 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: 상태(enum) 컬럼을 도메인 enum 값 집합으로, 금액 컬럼을 부호 규칙으로 DB 레벨에서 강제한다.
-- 왜: V1 베이스라인은 Hibernate export 라 CHECK 가 0건이었다(varchar(20) status 는 어떤 문자열도 허용).
--     order-service(opslab)는 V45 등에서 동일 CHECK 를 이미 갖는다 — settlement_db 만 무방비였다.
--
-- ★ enum 값 정본: 각 CHECK 의 값 목록은 도메인 enum 클래스와 1:1 로 맞췄다(드리프트 시 빌드가 아닌
--     런타임 INSERT 가 깨지므로, enum 에 값 추가 시 여기도 반드시 확장할 것):
--     SettlementStatus / SettlementAdjustmentStatus / PayoutStatus / ChargebackStatus·Source·Reason /
--     LedgerStatus·ReferenceType·LedgerEntryType·AccountType / LedgerOutboxStatus·LedgerTaskType /
--     ReconciliationRunStatus / DiscrepancyStatus·DiscrepancyType.
-- ★ settlement_adjustments.amount 에는 부호 CHECK 를 걸지 않는다 — 조정(역정산)은 음수 row 가 정상
--     (환불·분쟁 인정 시 음수 조정 생성). 부호 강제는 자금 사고를 유발하므로 의도적으로 제외한다.
-- ★ pg_reconciliation_discrepancies.difference 도 부호 CHECK 제외 — internal−pg 라 음수가 정상.
--
-- 안전성: 전부 NOT VALID 추가 후 VALIDATE. status 는 항상 enum.name() 으로 적재되고(예: 정산 매퍼는
--   domain.getStatus().name()), 빈/기존 DB 모두 위반 행이 없어 VALIDATE 가 통과한다.
--   (SettlementJpaEntity 의 @PrePersist status="PENDING" 기본값은 매퍼가 항상 status 를 채우므로
--   실행되지 않는 휴면 경로다.)
--
-- ★ settlements.status 값 집합은 3계층 합집합이다 (코어 enum 5종만으로 좁히면 실존 읽기 경로가 깨진다):
--     ① 코어 상태머신(SettlementStatus): REQUESTED/PROCESSING/DONE/FAILED/CANCELED — 신규 기록은 이 5종만 생성.
--     ② 관리자 승인 워크플로(읽기 경로 실존 — SettlementQueryRepositoryImpl.findByApprovalStatus 가
--        status IN ('WAITING_APPROVAL','APPROVED','REJECTED') 를 질의): WAITING_APPROVAL/APPROVED/REJECTED.
--     ③ 레거시(읽기 경로 실존 — SettlementBatchHealthPersistenceAdapter 가 'PENDING'/'CONFIRMED' 를 집계,
--        구 볼륨 DB 에 잔존 가능): PENDING/CONFIRMED.
--   → CHECK 는 ①∪②∪③ 10종을 허용해 오타·쓰레기 값만 차단한다. ②③ 읽기 경로가 제거되고 데이터가
--     정규화되면 CHECK 를 ①로 좁히는 후속 마이그레이션을 추가할 것.

-- ───────────────────────── settlements ─────────────────────────
ALTER TABLE public.settlements
    ADD CONSTRAINT chk_settlements_status
        CHECK (status IN ('REQUESTED','PROCESSING','DONE','FAILED','CANCELED',
                          'WAITING_APPROVAL','APPROVED','REJECTED',
                          'PENDING','CONFIRMED')) NOT VALID,
    ADD CONSTRAINT chk_settlements_amounts
        CHECK (payment_amount >= 0 AND refunded_amount >= 0 AND commission >= 0
               AND net_amount >= 0 AND holdback_amount >= 0) NOT VALID,
    ADD CONSTRAINT chk_settlements_commission_rate
        CHECK (commission_rate >= 0 AND commission_rate <= 1) NOT VALID,
    ADD CONSTRAINT chk_settlements_holdback_rate
        CHECK (holdback_rate >= 0 AND holdback_rate <= 1) NOT VALID;
ALTER TABLE public.settlements VALIDATE CONSTRAINT chk_settlements_status;
ALTER TABLE public.settlements VALIDATE CONSTRAINT chk_settlements_amounts;
ALTER TABLE public.settlements VALIDATE CONSTRAINT chk_settlements_commission_rate;
ALTER TABLE public.settlements VALIDATE CONSTRAINT chk_settlements_holdback_rate;

-- ─────────────────── settlement_adjustments ───────────────────
-- (source at-most-one 제약은 V20260712120000 이 이미 소유. amount 부호 CHECK 없음 — 위 주석 참조.)
ALTER TABLE public.settlement_adjustments
    ADD CONSTRAINT chk_adjustments_status
        CHECK (status IN ('PENDING','CONFIRMED')) NOT VALID;
ALTER TABLE public.settlement_adjustments VALIDATE CONSTRAINT chk_adjustments_status;

-- ───────────────────────── payouts ─────────────────────────
ALTER TABLE public.payouts
    ADD CONSTRAINT chk_payouts_status
        CHECK (status IN ('REQUESTED','SENDING','COMPLETED','FAILED','CANCELED')) NOT VALID,
    ADD CONSTRAINT chk_payouts_amount
        CHECK (amount >= 0) NOT VALID;
ALTER TABLE public.payouts VALIDATE CONSTRAINT chk_payouts_status;
ALTER TABLE public.payouts VALIDATE CONSTRAINT chk_payouts_amount;

-- ───────────────────────── chargebacks ─────────────────────────
ALTER TABLE public.chargebacks
    ADD CONSTRAINT chk_chargebacks_status
        CHECK (status IN ('OPEN','ACCEPTED','REJECTED')) NOT VALID,
    ADD CONSTRAINT chk_chargebacks_source
        CHECK (source IN ('PG_WEBHOOK','MANUAL')) NOT VALID,
    ADD CONSTRAINT chk_chargebacks_reason
        CHECK (reason_code IN ('FRAUD','DUPLICATE','NOT_RECEIVED','PRODUCT_NOT_AS_DESCRIBED','OTHER')) NOT VALID,
    ADD CONSTRAINT chk_chargebacks_amount
        CHECK (amount >= 0) NOT VALID;
ALTER TABLE public.chargebacks VALIDATE CONSTRAINT chk_chargebacks_status;
ALTER TABLE public.chargebacks VALIDATE CONSTRAINT chk_chargebacks_source;
ALTER TABLE public.chargebacks VALIDATE CONSTRAINT chk_chargebacks_reason;
ALTER TABLE public.chargebacks VALIDATE CONSTRAINT chk_chargebacks_amount;

-- ───────────────────────── ledger_entries ─────────────────────────
-- order V45 동형: amount>0, 차변<>대변, status/reference_type 값 강제 + entry_type·account 값 강제.
ALTER TABLE public.ledger_entries
    ADD CONSTRAINT chk_ledger_status
        CHECK (status IN ('PENDING','POSTED','REVERSED')) NOT VALID,
    ADD CONSTRAINT chk_ledger_ref_type
        CHECK (reference_type IN ('SETTLEMENT','REFUND')) NOT VALID,
    ADD CONSTRAINT chk_ledger_entry_type
        CHECK (entry_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','REFUND_REVERSED',
                              'COMMISSION_RECOGNIZED','PAYOUT_EXECUTED')) NOT VALID,
    ADD CONSTRAINT chk_ledger_debit_account
        CHECK (debit_account IN ('ACCOUNTS_RECEIVABLE','ACCOUNTS_PAYABLE','REVENUE','COMMISSION_REVENUE',
                                 'COMMISSION_EXPENSE','SALES_REFUND','CASH')) NOT VALID,
    ADD CONSTRAINT chk_ledger_credit_account
        CHECK (credit_account IN ('ACCOUNTS_RECEIVABLE','ACCOUNTS_PAYABLE','REVENUE','COMMISSION_REVENUE',
                                  'COMMISSION_EXPENSE','SALES_REFUND','CASH')) NOT VALID,
    ADD CONSTRAINT chk_ledger_amount
        CHECK (amount > 0) NOT VALID,
    ADD CONSTRAINT chk_ledger_diff_accounts
        CHECK (debit_account <> credit_account) NOT VALID;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_status;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_ref_type;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_entry_type;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_debit_account;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_credit_account;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_amount;
ALTER TABLE public.ledger_entries VALIDATE CONSTRAINT chk_ledger_diff_accounts;

-- ───────────────────────── ledger_outbox ─────────────────────────
ALTER TABLE public.ledger_outbox
    ADD CONSTRAINT chk_ledger_outbox_status
        CHECK (status IN ('PENDING','DONE','FAILED')) NOT VALID,
    ADD CONSTRAINT chk_ledger_outbox_task_type
        CHECK (task_type IN ('CREATE_ENTRY','REVERSE_ENTRY')) NOT VALID;
ALTER TABLE public.ledger_outbox VALIDATE CONSTRAINT chk_ledger_outbox_status;
ALTER TABLE public.ledger_outbox VALIDATE CONSTRAINT chk_ledger_outbox_task_type;

-- ─────────────────── pg_reconciliation_runs ───────────────────
ALTER TABLE public.pg_reconciliation_runs
    ADD CONSTRAINT chk_pg_recon_run_status
        CHECK (status IN ('RUNNING','COMPLETED','FAILED')) NOT VALID;
ALTER TABLE public.pg_reconciliation_runs VALIDATE CONSTRAINT chk_pg_recon_run_status;

-- ─────────────── pg_reconciliation_discrepancies ───────────────
ALTER TABLE public.pg_reconciliation_discrepancies
    ADD CONSTRAINT chk_pg_recon_disc_status
        CHECK (status IN ('PENDING','APPROVED','REJECTED','AUTO_CORRECTED')) NOT VALID,
    ADD CONSTRAINT chk_pg_recon_disc_type
        CHECK (type IN ('AMOUNT_MISMATCH','MISSING_INTERNAL','MISSING_PG','DUPLICATE','ROUNDING_DIFF')) NOT VALID,
    ADD CONSTRAINT chk_pg_recon_disc_amounts
        CHECK ((internal_amount IS NULL OR internal_amount >= 0)
               AND (pg_amount IS NULL OR pg_amount >= 0)) NOT VALID;
ALTER TABLE public.pg_reconciliation_discrepancies VALIDATE CONSTRAINT chk_pg_recon_disc_status;
ALTER TABLE public.pg_reconciliation_discrepancies VALIDATE CONSTRAINT chk_pg_recon_disc_type;
ALTER TABLE public.pg_reconciliation_discrepancies VALIDATE CONSTRAINT chk_pg_recon_disc_amounts;
