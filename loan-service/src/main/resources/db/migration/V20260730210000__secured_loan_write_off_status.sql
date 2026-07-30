-- V20260730210000: 담보대출 상각 상태 추가 (Phase 2)
--
-- 담보 실행(처분·대위변제) 결과 회수 부족분이 남으면 WRITTEN_OFF 로 종결한다.
-- SecuredLoanStatus enum 이 정본이며, CHECK 를 확장하지 않으면 상각 UPDATE 가
-- check_violation(23514)으로 실패한다 — 상태·유형 enum 추가와 CHECK 확장을 항상 함께 한다.
-- 정합은 SchemaEnumContractIT(chk_secured_loan_status ↔ enum 정확 일치)가 빌드 시점에 대조한다.
ALTER TABLE secured_loans DROP CONSTRAINT IF EXISTS chk_secured_loan_status;
ALTER TABLE secured_loans
    ADD CONSTRAINT chk_secured_loan_status
        CHECK (status IN ('REQUESTED', 'APPROVED', 'DISBURSED', 'OVERDUE', 'DEFAULTED',
                          'REPAID', 'REJECTED', 'WRITTEN_OFF')) NOT VALID;
ALTER TABLE secured_loans VALIDATE CONSTRAINT chk_secured_loan_status;
