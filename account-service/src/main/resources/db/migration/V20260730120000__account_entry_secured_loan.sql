-- V20260730120000: 담보/개인신용 대출(SecuredLoan) GL 소비 매핑 — 계정·ref_type·owner CHECK 확장
--
-- loan 담보대출 Phase 2 잔여 이월분: account 가 lemuel.loan.secured_loan_disbursed / .secured_loan_repaid
-- 를 소비해 차주(BORROWER) 원장으로 집계한다. 법인 대출 선례와 동형으로 **원금만** 분개하고
-- (이자·중도상환수수료는 loan 자체 원장 소관), 완제 이벤트의 principal 은 계약 원금이라
-- 실행 전표와 동액 — 회차·중도상환 경로와 무관하게 SECURED_LOAN_RECEIVABLE 이 0 으로 닫힌다.
--
-- 확장 4종 (값 집합의 정본은 AccountEntry 팩토리 20종 — SchemaEnumContractIT 가 CHECK↔팩토리 일치 검증):
--   A) debit/credit 계정 CHECK: SECURED_LOAN_RECEIVABLE 추가
--   B) ref_type CHECK: SECURED_LOAN_DISBURSED / SECURED_LOAN_REPAID 추가
--   C) owner_type CHECK(account_entries·account_balances): BORROWER 추가
--      — 차주는 개인·법인 공통 userId 로 식별되어 SELLER(sellerId)와도 CORPORATE(stockCode)와도 다르다
--   D) owner_id 형식 CHECK: BORROWER = borrowerUserId 숫자 문자열('^[0-9]+$')

-- ── A) GL 계정 열거 CHECK 재작성 — SECURED_LOAN_RECEIVABLE 추가 ─────────────────────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_credit_account;

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_debit_account
        CHECK (debit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                 'SECURED_LOAN_RECEIVABLE',
                                 'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                 'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                 'WITHHOLDING_PAYABLE')) NOT VALID;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_credit_account
        CHECK (credit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                  'SECURED_LOAN_RECEIVABLE',
                                  'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                  'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                  'WITHHOLDING_PAYABLE')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

-- ── B) ref_type CHECK 확장 — SECURED_LOAN_DISBURSED / SECURED_LOAN_REPAID (팩토리 20종) ─────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING',
                            'SETTLEMENT_HOLDBACK_RECOGNIZED','HOLDBACK_RELEASED','HOLDBACK_CONSUMED',
                            'SETTLEMENT_ADJUSTED','SETTLEMENT_CANCELED_PAYABLE','SETTLEMENT_CANCELED_HOLDBACK',
                            'RECOVERY_OPENED','RECOVERY_OFFSET','WITHHOLDING_ACCRUED',
                            'PAYOUT_ADVANCE','SECURED_LOAN_DISBURSED','SECURED_LOAN_REPAID')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리 20종이 정본(ADR 0026 Option ① + ADR 0029 §B + 감사 MED-3 + 담보대출 GL 소비). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';

-- ── C) owner_type CHECK 확장 — BORROWER (양 테이블 동일 도메인) ────────────────────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_owner_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_owner_type
        CHECK (owner_type IN ('SELLER', 'CORPORATE', 'BORROWER')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_owner_type;

ALTER TABLE account_balances DROP CONSTRAINT chk_account_balance_owner_type;
ALTER TABLE account_balances
    ADD CONSTRAINT chk_account_balance_owner_type
        CHECK (owner_type IN ('SELLER', 'CORPORATE', 'BORROWER')) NOT VALID;
ALTER TABLE account_balances VALIDATE CONSTRAINT chk_account_balance_owner_type;

-- ── D) owner_id 다형 자연키 형식 CHECK — BORROWER = borrowerUserId 숫자 문자열 ──────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_owner_id_format;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_owner_id_format
        CHECK (
            (owner_type = 'SELLER'    AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'CORPORATE' AND owner_id ~ '^[0-9A-Z]{6}$')
         OR (owner_type = 'BORROWER'  AND owner_id ~ '^[0-9]+$')
        ) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_owner_id_format;

COMMENT ON COLUMN account_entries.owner_id IS
    '다형 자연키 — SELLER=sellerId(숫자), CORPORATE=stockCode(6자리), BORROWER=borrowerUserId(숫자). 형식은 chk_account_entry_owner_id_format 이 강제.';
