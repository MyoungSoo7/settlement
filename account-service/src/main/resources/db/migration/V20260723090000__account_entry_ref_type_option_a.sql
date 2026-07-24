-- V20260723090000: account_entries.ref_type CHECK 확장 — ADR 0026 Option A (GL 현금 폐루프)
--
-- Option A 로 계정계 현금 흐름을 닫으면서 두 개의 신규 분개 refType 이 추가된다:
--   · PAYOUT_COMPLETED          : 셀러 정산금 실지급 완료 → DR SELLER_PAYABLE / CR CASH (settlement 발행 소비)
--   · SETTLEMENT_SCHED_CLEARING : cut-over 잔존 정산예정금 청산 백필 → DR CASH / CR SETTLEMENT_SCHEDULED
-- 기존 6개 값(SETTLEMENT_CREATED/CONFIRMED, LOAN_DISBURSED/REPAID, CORP_LOAN_DISBURSED, INVESTMENT_EXECUTED)은
-- cut-over 이전 적재분 보존을 위해 그대로 유지한다(값 집합의 정본은 AccountEntry 팩토리 8종).
--
-- 주: settlement.confirmed 는 Option A 에서 GL 무전표(전기 없음)지만, SETTLEMENT_CONFIRMED 는 과거 적재
-- 행이 존재하므로 CHECK 에서 제거하지 않는다(제거 시 VALIDATE 가 기존 행에서 실패).

ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리 8종이 정본(ADR 0026 Option A: PAYOUT_COMPLETED·SETTLEMENT_SCHED_CLEARING 포함). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';
