-- V20260730230000: 담보대출 원금 감소 건별 전기(#183) — ref_type CHECK 확장
--
-- 직전 마이그레이션(V20260730120000)은 완제 시점에 계약 원금 전액을 대변 처리하는 설계를 전제로
-- SECURED_LOAN_DISBURSED / SECURED_LOAN_REPAID 두 값만 허용했다. 그 설계는 회차·중도상환으로
-- loan 쪽 채권이 줄어드는 동안 계정계가 최초 원금을 그대로 들고 있게 만들어(완제 전까지 시산·
-- 실체화 잔액이 틀림) #183 으로 교정됐다.
--
-- 이제 원금 감소를 건별로 전기하므로 SECURED_LOAN_PRINCIPAL_REPAID 를 허용해야 한다.
-- 이 값이 빠져 있으면 새 컨슈머의 모든 insert 가 chk_account_entry_ref_type 에 걸려
-- 대출 상환이 GL 에 한 건도 반영되지 않는다(코드리뷰 지적).
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
                            'SECURED_LOAN_PRINCIPAL_REPAID')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리가 정본(ADR 0026 Option ① + ADR 0029 §B + 감사 MED-3 + 담보대출 GL 소비 + #183 원금 건별 전기). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';
