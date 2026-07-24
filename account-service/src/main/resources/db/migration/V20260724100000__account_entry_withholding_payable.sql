-- V20260724100000: 원천징수 예수금(WITHHOLDING_PAYABLE) GL 계정 신설
--
-- 독립 GL 감사 HIGH #4(원천징수 미실현) 봉합: settlement-service 가 정산 확정(payout 산정) 시점에
-- 개인 셀러 원천징수를 실제 지급액에서 공제하도록 바뀌었다(ADR 0029 §B, 2026-07-24 정정). 그 결과
-- payoutCompleted 의 DR SELLER_PAYABLE 금액이 (I−O−W) 로 줄어 SELLER_PAYABLE 에 W(withholding) 만큼
-- 잔여가 남는데, 이 전표(WITHHOLDING_ACCRUED: Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE)가 그 잔여를
-- 닫아 통제계정 폐루프(ADR 0026 Option ①)를 유지한다.
--
-- GlAccount enum 과 1:1(SchemaEnumContractIT 가 정확 일치 검증). 기존 8 → 9 계정.
-- ref_type CHECK 확장: WITHHOLDING_ACCRUED 1종 추가(AccountEntry 팩토리 17종).

ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_credit_account;

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_debit_account
        CHECK (debit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                 'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                 'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                 'WITHHOLDING_PAYABLE')) NOT VALID;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_credit_account
        CHECK (credit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                  'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                  'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                  'WITHHOLDING_PAYABLE')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING',
                            'SETTLEMENT_HOLDBACK_RECOGNIZED','HOLDBACK_RELEASED','HOLDBACK_CONSUMED',
                            'SETTLEMENT_ADJUSTED','SETTLEMENT_CANCELED_PAYABLE','SETTLEMENT_CANCELED_HOLDBACK',
                            'RECOVERY_OPENED','RECOVERY_OFFSET','WITHHOLDING_ACCRUED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리 17종이 정본(ADR 0026 Option ① + ADR 0029 §B 원천징수 확장). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';
