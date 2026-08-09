-- V20260809030500: 수신 상품(정기예금·적금·퇴직연금) GL 소비 매핑 — 계정·ref_type·owner CHECK 확장
--
-- account-service 는 지금까지 여신(대출)·정산·투자만 원장에 집계했고 owner 는 셀러·법인·차주뿐이었다.
-- 은행형 수신 3종(정기예금 TimeDeposit / 적금 InstallmentSavings / 퇴직연금 RetirementPension)이
-- 추가되면서, 가입자에게 지급할 의무를 부채 계정으로, 그 이자를 비용 계정으로 인식해야 한다.
--
-- 설계 전제 두 가지 — 이 CHECK 집합이 그대로 반영한다:
--   1) **이자는 주기 accrual 을 두지 않는다.** 만기·해지·수급 시점에 일괄 확정해 한 번 전기한다.
--      따라서 ref_type 에 '..._ACCRUED' 류가 없고 '..._INTEREST' 확정분만 있다.
--   2) **원금 이동과 이자 인식은 절대 한 전표에 섞지 않는다.** 각각 별도 ref_type 자연키로 분리 전기해
--      원금 흐름과 손익 흐름이 원장에서 독립적으로 추적된다.
--
-- 확장 4종 (값 집합의 정본은 AccountEntry 팩토리 31종 — SchemaEnumContractIT 가 CHECK↔팩토리 일치 검증):
--   A) debit/credit 계정 CHECK: TIME_DEPOSIT_LIABILITY / INSTALLMENT_SAVINGS_LIABILITY /
--      RETIREMENT_PENSION_LIABILITY / INTEREST_EXPENSE 추가
--   B) ref_type CHECK: 수신 10종 추가
--   C) owner_type CHECK(account_entries·account_balances): DEPOSITOR 추가
--      — 수신 가입자는 셀러도 차주도 아니고, 제도(예금·적금·연금)별로 쪼개지 않는다(하나의 자연인 단위)
--   D) owner_id 형식 CHECK: DEPOSITOR = userId 숫자 문자열('^[0-9]+$')

-- ── A) GL 계정 열거 CHECK 재작성 — 수신부채 3종 + 수신이자비용 ─────────────────────────────
-- 차변·대변 CHECK 는 같은 값 집합을 쓴다(선례 유지). 어떤 계정이 어느 변에 오는지는 GlAccount 의
-- AccountSide 와 AccountEntry 팩토리가 강제하며, SQL 에 방향을 복제하면 조용한 드리프트가 생긴다.
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_credit_account;

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_debit_account
        CHECK (debit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                 'SECURED_LOAN_RECEIVABLE',
                                 'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                 'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                 'WITHHOLDING_PAYABLE',
                                 'TIME_DEPOSIT_LIABILITY','INSTALLMENT_SAVINGS_LIABILITY',
                                 'RETIREMENT_PENSION_LIABILITY','INTEREST_EXPENSE')) NOT VALID;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_credit_account
        CHECK (credit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                  'SECURED_LOAN_RECEIVABLE',
                                  'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                  'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                  'WITHHOLDING_PAYABLE',
                                  'TIME_DEPOSIT_LIABILITY','INSTALLMENT_SAVINGS_LIABILITY',
                                  'RETIREMENT_PENSION_LIABILITY','INTEREST_EXPENSE')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

-- ── B) ref_type CHECK 확장 — 수신 10종 (팩토리 31종) ────────────────────────────────────────
--   정기예금: 가입(원금 수입) / 이자확정 / 지급(만기·중도 공통)
--   적금:     회차납입 / 이자확정 / 지급
--   퇴직연금: 부담금납입 / 운용수익확정 / 연금·일시금 수급 / 중도인출(법정 사유 한정)
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING',
                            'SETTLEMENT_HOLDBACK_RECOGNIZED','HOLDBACK_RELEASED','HOLDBACK_CONSUMED',
                            'SETTLEMENT_ADJUSTED','SETTLEMENT_CANCELED_PAYABLE','SETTLEMENT_CANCELED_HOLDBACK',
                            'RECOVERY_OPENED','RECOVERY_OFFSET','WITHHOLDING_ACCRUED',
                            'PAYOUT_ADVANCE','SECURED_LOAN_DISBURSED','SECURED_LOAN_REPAID',
                            'SECURED_LOAN_PRINCIPAL_REPAID',
                            'TIME_DEPOSIT_OPENED','TIME_DEPOSIT_INTEREST','TIME_DEPOSIT_CLOSED',
                            'SAVINGS_INSTALLMENT_PAID','SAVINGS_INTEREST','SAVINGS_CLOSED',
                            'PENSION_CONTRIBUTION_PAID','PENSION_INTEREST','PENSION_BENEFIT_PAID',
                            'PENSION_MID_WITHDRAWN')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리 31종이 정본(ADR 0026 Option ① + ADR 0029 §B + 감사 MED-3 + 담보대출 GL 소비 + #183 원금 건별 전기 + 수신 3종). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';

-- ── C) owner_type CHECK 확장 — DEPOSITOR (양 테이블 동일 도메인) ───────────────────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_owner_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_owner_type
        CHECK (owner_type IN ('SELLER', 'CORPORATE', 'BORROWER', 'DEPOSITOR')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_owner_type;

ALTER TABLE account_balances DROP CONSTRAINT chk_account_balance_owner_type;
ALTER TABLE account_balances
    ADD CONSTRAINT chk_account_balance_owner_type
        CHECK (owner_type IN ('SELLER', 'CORPORATE', 'BORROWER', 'DEPOSITOR')) NOT VALID;
ALTER TABLE account_balances VALIDATE CONSTRAINT chk_account_balance_owner_type;

-- ── D) owner_id 다형 자연키 형식 CHECK — DEPOSITOR = userId 숫자 문자열 ────────────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_owner_id_format;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_owner_id_format
        CHECK (
            (owner_type = 'SELLER'    AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'CORPORATE' AND owner_id ~ '^[0-9A-Z]{6}$')
         OR (owner_type = 'BORROWER'  AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'DEPOSITOR' AND owner_id ~ '^[0-9]+$')
        ) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_owner_id_format;

COMMENT ON COLUMN account_entries.owner_id IS
    '다형 자연키 — SELLER=sellerId(숫자), CORPORATE=stockCode(6자리), BORROWER=borrowerUserId(숫자), DEPOSITOR=userId(숫자). 형식은 chk_account_entry_owner_id_format 이 강제.';
