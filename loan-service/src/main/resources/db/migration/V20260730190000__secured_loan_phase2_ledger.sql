-- V20260730190000: 담보대출 Phase 2 전표 DB 지원 (원장 계약 확장)
--
-- LoanLedgerEntry 팩토리가 7종 늘었다:
--   securedGuaranteeFee       → SEC_GUARANTEE_FEE  (보증료 선취, 대출 1건당 1회)
--   securedEarlyRepaymentFee  → SEC_EARLY_FEE      (중도상환수수료, N회 가능)
--   securedCollateralDisposal → SEC_DISPOSAL       (담보 처분 회수, 1회)
--   securedDisposalShortfall  → SEC_DISPOSAL_LOSS  (처분 부족분 손실, 1회)
--   securedDisposalSurplus    → SEC_DISPOSAL_GAIN  (처분 초과분 이익, 1회)
--   securedSubrogation        → SEC_SUBROGATION    (보증기관 대위변제 회수, 1회)
--   securedWriteOff           → SEC_BAD_DEBT       (미회수 상각, 1회)
--
-- 신규 계정과목 3종(GUARANTEE_FEE_EXPENSE·COLLATERAL_DISPOSAL_LOSS·COLLATERAL_DISPOSAL_GAIN)은
-- debit/credit 이 VARCHAR(30) 이고 값 CHECK 가 없어(제약은 debit <> credit 뿐) 스키마 변경이 없다.
-- 계정명 최장 24자로 30자 한도 안이며, 이 여유는 단위테스트가 지킨다.

-- ① ref_type CHECK 를 팩토리 실값 17종으로 확장.
--    미확장 시 신규 전표 INSERT 가 전부 check_violation(23514)으로 실패한다 —
--    V20260717200000/V20260724130000/V20260730120000 이 반복해 겪은 갭이라 팩토리 추가와 함께 넓힌다.
--    신규 7종은 지금까지 INSERT 가 없던 값이라 VALIDATE 가 즉시 통과한다.
--    팩토리↔CHECK 정합은 SchemaEnumContractIT 가 빌드 시점에 대조한다.
ALTER TABLE loan_ledger_entries DROP CONSTRAINT IF EXISTS chk_loan_ledger_ref_type;
ALTER TABLE loan_ledger_entries
    ADD CONSTRAINT chk_loan_ledger_ref_type
        CHECK (ref_type IN ('DISBURSE', 'FEE', 'REPAYMENT', 'CORP_DISBURSE', 'CORP_FEE',
                            'CORP_REPAYMENT', 'BAD_DEBT',
                            'SEC_DISBURSE', 'SEC_REPAYMENT', 'SEC_INTEREST',
                            'SEC_GUARANTEE_FEE', 'SEC_EARLY_FEE', 'SEC_DISPOSAL',
                            'SEC_DISPOSAL_LOSS', 'SEC_DISPOSAL_GAIN', 'SEC_SUBROGATION',
                            'SEC_BAD_DEBT')) NOT VALID;
ALTER TABLE loan_ledger_entries VALIDATE CONSTRAINT chk_loan_ledger_ref_type;

-- ② 중복 분개 유니크에서 SEC_EARLY_FEE 를 추가 제외한다.
--    중도상환은 약정 기간 중 여러 번 가능하므로 같은 4-튜플이 반복된다.
--    처분·대위변제·상각은 담보/대출 1건당 1회라 유니크에 남겨 이중 회수·이중 상각을 계속 차단한다.
DROP INDEX IF EXISTS uq_loan_ledger_reference_accounts;
CREATE UNIQUE INDEX IF NOT EXISTS uq_loan_ledger_reference_accounts
    ON loan_ledger_entries (ref_type, ref_id, debit, credit)
    WHERE ref_type NOT IN ('CORP_REPAYMENT', 'SEC_REPAYMENT', 'SEC_INTEREST', 'SEC_EARLY_FEE');

COMMENT ON INDEX uq_loan_ledger_reference_accounts IS
    '동일 참조(ref_type,ref_id)·계정쌍 이중 기표 차단. 단 반복 발생 전표(CORP_REPAYMENT·SEC_REPAYMENT·'
    'SEC_INTEREST·SEC_EARLY_FEE)는 대출 1건당 N회 정상이라 제외 — 처분·대위변제·상각은 1회라 유지.';
