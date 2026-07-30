-- V20260730120000: 담보/개인신용 대출 (loan-service 자체 DB) — Phase 1
--
-- 신규 테이블만 CREATE 한다. 기존 loan_advances·corporate_loans 는 손대지 않는다
-- (상장사 종목코드에 차주가 묶인 기존 구조를 건드리지 않고 별도 애그리거트로 분리한 설계).
--
-- collaterals   : 담보물. 평가액은 설정 시점 스냅샷으로 보존한다 — 재평가·마진콜(Phase 2)이
--                 도입되어도 이 값을 덮어쓰지 않고 이력 행을 추가해야 이미 실행된 대출의
--                 심사 근거가 재현된다.
-- secured_loans : 주택담보(MORTGAGE)·개인신용(PERSONAL_CREDIT) 대출. 담보는 optional 이며,
--                 상품별 필수 요소를 CHECK 로 DB 에서도 강제해 도메인과 이중 방어한다.
--                 상태머신: REQUESTED → APPROVED → DISBURSED → REPAID,
--                 분기 → REJECTED, DISBURSED → OVERDUE → DEFAULTED, 각 단계 → REPAID.

CREATE TABLE IF NOT EXISTS collaterals (
    id              BIGSERIAL      PRIMARY KEY,
    type            VARCHAR(20)    NOT NULL,   -- REAL_ESTATE (Phase 2: 보증서·금융자산)
    description     VARCHAR(500)   NOT NULL,   -- 담보물 표시(부동산 소재지 등)
    appraised_value NUMERIC(19, 2) NOT NULL,   -- 감정평가액 스냅샷
    appraised_at    TIMESTAMP      NOT NULL,   -- 평가 시각(스냅샷 기준 시점)
    status          VARCHAR(20)    NOT NULL,   -- PLEDGED/ACTIVE/RELEASED
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_collateral_appraised_value CHECK (appraised_value > 0),
    CONSTRAINT chk_collateral_type   CHECK (type IN ('REAL_ESTATE')),
    CONSTRAINT chk_collateral_status CHECK (status IN ('PLEDGED', 'ACTIVE', 'RELEASED'))
);

CREATE TABLE IF NOT EXISTS secured_loans (
    id                       BIGSERIAL      PRIMARY KEY,
    -- 차주(Borrower VO 를 평탄화해 보관 — 신청 시점 스냅샷)
    borrower_type            VARCHAR(20)    NOT NULL,   -- INDIVIDUAL/CORPORATE
    borrower_user_id         BIGINT         NOT NULL,   -- JWT 주체(소유권 스코핑, IDOR 방지)
    borrower_name            VARCHAR(255)   NOT NULL,
    borrower_registration_no VARCHAR(10),               -- 법인 전용(개인은 NULL)
    -- 상품·담보
    product_type             VARCHAR(20)    NOT NULL,   -- MORTGAGE/PERSONAL_CREDIT
    collateral_id            BIGINT         REFERENCES collaterals (id),
    -- 계약 조건
    principal                NUMERIC(19, 2) NOT NULL,
    term_months              INT            NOT NULL,
    annual_rate_percent      NUMERIC(6, 3)  NOT NULL,   -- 기준금리 + 가산금리(신청 시점 스냅샷)
    repayment_method         VARCHAR(20)    NOT NULL,   -- BULLET/EQUAL_PRINCIPAL/EQUAL_PAYMENT
    -- 심사 근거 스냅샷(개인신용 전용)
    credit_score             INT,                       -- 외부 CB 점수
    credit_grade             VARCHAR(1),                -- A~E
    -- 잔액·상태
    outstanding              NUMERIC(19, 2) NOT NULL,   -- 실행 시 principal, 상환 시 감소
    status                   VARCHAR(20)    NOT NULL,
    created_at               TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_secured_loan_principal   CHECK (principal > 0),
    CONSTRAINT chk_secured_loan_term_months CHECK (term_months >= 1),
    CONSTRAINT chk_secured_loan_rate        CHECK (annual_rate_percent >= 0),
    CONSTRAINT chk_secured_loan_outstanding CHECK (outstanding >= 0),
    CONSTRAINT chk_secured_loan_product     CHECK (product_type IN ('MORTGAGE', 'PERSONAL_CREDIT')),
    CONSTRAINT chk_secured_loan_method      CHECK (repayment_method IN
        ('BULLET', 'EQUAL_PRINCIPAL', 'EQUAL_PAYMENT')),
    CONSTRAINT chk_secured_loan_status      CHECK (status IN
        ('REQUESTED', 'APPROVED', 'DISBURSED', 'OVERDUE', 'DEFAULTED', 'REPAID', 'REJECTED')),
    CONSTRAINT chk_secured_loan_borrower_type CHECK (borrower_type IN ('INDIVIDUAL', 'CORPORATE')),

    -- 차주 유형별 불변식: 법인은 사업자번호 필수, 개인은 보유 금지(도메인 Borrower 와 동일 규칙).
    CONSTRAINT chk_secured_loan_borrower_shape CHECK (
        (borrower_type = 'CORPORATE'  AND borrower_registration_no IS NOT NULL)
     OR (borrower_type = 'INDIVIDUAL' AND borrower_registration_no IS NULL)
    ),

    -- 상품별 불변식: 담보형은 담보 필수, 신용형은 담보 없이 CB 점수·등급 필수.
    -- 도메인 팩토리가 이미 막지만, 백필·수기 INSERT 같은 우회 경로까지 막으려면 DB 제약이 필요하다.
    CONSTRAINT chk_secured_loan_product_shape CHECK (
        (product_type = 'MORTGAGE' AND collateral_id IS NOT NULL)
     OR (product_type = 'PERSONAL_CREDIT' AND collateral_id IS NULL
         AND credit_score IS NOT NULL AND credit_grade IS NOT NULL)
    )
);

-- 차주 본인 대출 목록 조회(최신순) — 소유권 스코핑된 조회의 주 경로.
CREATE INDEX IF NOT EXISTS idx_secured_loans_borrower
    ON secured_loans (borrower_user_id, id DESC);

-- 연체 판정 배치가 실행 중인 대출만 훑도록 하는 부분 인덱스.
CREATE INDEX IF NOT EXISTS idx_secured_loans_active_status
    ON secured_loans (status, id)
    WHERE status IN ('DISBURSED', 'OVERDUE');

-- ── 원장 계약 확장 (V20260724130000 과 같은 갭을 미리 봉합) ─────────────────────
-- LoanLedgerEntry 팩토리가 3종 늘었다:
--   securedDisbursement      → ref_type='SEC_DISBURSE'  (실행, ref_id=loanId, 대출 1건당 1회)
--   securedPrincipalRepayment→ ref_type='SEC_REPAYMENT' (원금 상환, ref_id=loanId, 회차마다 N회)
--   securedInterestIncome    → ref_type='SEC_INTEREST'  (이자 수익, ref_id=loanId, 회차마다 N회)
-- 이를 반영하지 않으면 V20260717200000/V20260724130000 이 겪은 두 갭이 그대로 재발한다.

-- ① ref_type CHECK 를 팩토리 실값 10종으로 확장. 신규 3종은 지금까지 INSERT 자체가 없던 값이라
--    기존 행에 존재하지 않으므로 VALIDATE 가 즉시 통과한다.
--    팩토리↔CHECK 정합은 SchemaEnumContractIT 가 빌드 시점에 대조한다.
ALTER TABLE loan_ledger_entries DROP CONSTRAINT IF EXISTS chk_loan_ledger_ref_type;
ALTER TABLE loan_ledger_entries
    ADD CONSTRAINT chk_loan_ledger_ref_type
        CHECK (ref_type IN ('DISBURSE', 'FEE', 'REPAYMENT', 'CORP_DISBURSE', 'CORP_FEE',
                            'CORP_REPAYMENT', 'BAD_DEBT',
                            'SEC_DISBURSE', 'SEC_REPAYMENT', 'SEC_INTEREST')) NOT VALID;
ALTER TABLE loan_ledger_entries VALIDATE CONSTRAINT chk_loan_ledger_ref_type;

-- ② 중복 분개 유니크에서 회차성 전표 2종을 추가 제외한다. 장기 분할상환은 같은
--    (ref_type, ref_id, debit, credit) 4-튜플이 회차 수만큼 정상 발생하므로, 제외하지 않으면
--    2회차 상환부터 중복 기표로 오판돼 막힌다(CORP_REPAYMENT 와 동일한 사유).
--    SEC_DISBURSE 는 대출 1건당 1회라 유니크에 남겨 이중지급 기표를 계속 차단한다.
DROP INDEX IF EXISTS uq_loan_ledger_reference_accounts;
CREATE UNIQUE INDEX IF NOT EXISTS uq_loan_ledger_reference_accounts
    ON loan_ledger_entries (ref_type, ref_id, debit, credit)
    WHERE ref_type NOT IN ('CORP_REPAYMENT', 'SEC_REPAYMENT', 'SEC_INTEREST');

COMMENT ON INDEX uq_loan_ledger_reference_accounts IS
    '동일 참조(ref_type,ref_id)·계정쌍 이중 기표 차단. 단 회차성 전표(CORP_REPAYMENT·SEC_REPAYMENT·'
    'SEC_INTEREST)는 대출 1건당 N회 정상 발생이라 제외 — 재요청 멱등은 애플리케이션 레벨에서 처리.';
