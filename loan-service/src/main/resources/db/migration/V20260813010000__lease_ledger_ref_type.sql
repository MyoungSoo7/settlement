-- V20260813010000: 리스·할부 개시 전표(LEASE_ACTIVATE)를 원장 CHECK 에 편입한다.
--
-- 팩토리를 추가하면서 chk_loan_ledger_ref_type 을 넓히지 않으면 첫 기표부터 check_violation(23514)로
-- 롤백된다 — V20260717200000/V20260724130000/V20260730120000/V20260730190000 이 반복해 겪은 갭이다.
-- 팩토리↔CHECK 정합은 SchemaEnumContractIT 가 빌드 시점에 대조한다.
--
-- LEASE_ACTIVATE 는 지금까지 INSERT 가 없던 값이라 VALIDATE 가 즉시 통과한다.
-- 유니크(uq_loan_ledger_reference_accounts)는 그대로 둔다 — 계약 1건당 개시 기표는 1회이므로
-- (ref_type, ref_id, debit, credit) 중복 차단이 이중 기표 방어로 정확히 작동한다.

ALTER TABLE loan_ledger_entries DROP CONSTRAINT IF EXISTS chk_loan_ledger_ref_type;
ALTER TABLE loan_ledger_entries
    ADD CONSTRAINT chk_loan_ledger_ref_type
        CHECK (ref_type IN ('DISBURSE', 'FEE', 'REPAYMENT', 'CORP_DISBURSE', 'CORP_FEE',
                            'CORP_REPAYMENT', 'BAD_DEBT',
                            'SEC_DISBURSE', 'SEC_REPAYMENT', 'SEC_INTEREST',
                            'SEC_GUARANTEE_FEE', 'SEC_EARLY_FEE', 'SEC_DISPOSAL',
                            'SEC_DISPOSAL_LOSS', 'SEC_DISPOSAL_GAIN', 'SEC_SUBROGATION',
                            'SEC_BAD_DEBT',
                            'LEASE_ACTIVATE')) NOT VALID;
ALTER TABLE loan_ledger_entries VALIDATE CONSTRAINT chk_loan_ledger_ref_type;

COMMENT ON CONSTRAINT chk_loan_ledger_ref_type ON loan_ledger_entries IS
    'LoanLedgerEntry 팩토리 18종의 refType 집합 — 팩토리 추가 시 이 CHECK 를 함께 넓힌다(SchemaEnumContractIT 가 대조).';
