-- V20260730233000: 금융자산담보대출(FINANCIAL_ASSET) 상품 합류 — product CHECK 2종 확장
--
-- Phase 2 잔여 이월(담보평가 실연동)의 선행: 금융자산(예금·채권·주식) 담보 신청 경로가 열리면서
-- LoanProductType 에 FINANCIAL_ASSET 이 추가됐다. 담보유형 CHECK 가 두 번 당한 갭(enum 확장 시
-- CHECK 미확장 → 신규 값 INSERT 전부 check_violation)과 같은 종류라, enum 추가와 CHECK 확장을
-- 항상 붙여 둔다. 신규 값은 지금까지 INSERT 가 없어 VALIDATE 가 즉시 통과한다.
-- 값 집합 정본은 LoanProductType — SchemaEnumContractIT 가 CHECK↔enum 일치를 빌드 시점 검증.

-- ① product_type 열거 확장
ALTER TABLE secured_loans DROP CONSTRAINT chk_secured_loan_product;
ALTER TABLE secured_loans
    ADD CONSTRAINT chk_secured_loan_product
        CHECK (product_type IN ('MORTGAGE', 'FINANCIAL_ASSET', 'PERSONAL_CREDIT')) NOT VALID;
ALTER TABLE secured_loans VALIDATE CONSTRAINT chk_secured_loan_product;

-- ② 상품별 불변식 확장 — 금융자산담보도 담보 필수·CB 스냅샷 금지(담보형 공통 형태).
ALTER TABLE secured_loans DROP CONSTRAINT chk_secured_loan_product_shape;
ALTER TABLE secured_loans
    ADD CONSTRAINT chk_secured_loan_product_shape
        CHECK (
            (product_type IN ('MORTGAGE', 'FINANCIAL_ASSET') AND collateral_id IS NOT NULL)
         OR (product_type = 'PERSONAL_CREDIT' AND collateral_id IS NULL
             AND credit_score IS NOT NULL AND credit_grade IS NOT NULL)
        ) NOT VALID;
ALTER TABLE secured_loans VALIDATE CONSTRAINT chk_secured_loan_product_shape;

COMMENT ON COLUMN secured_loans.product_type IS
    '상품 유형(도메인 LoanProductType 정본). MORTGAGE=주택담보, FINANCIAL_ASSET=금융자산담보(예금·채권·주식), PERSONAL_CREDIT=개인신용.';
