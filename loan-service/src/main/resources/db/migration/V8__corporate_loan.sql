-- V8: 기업(상장사) 신용대출 테이블 (loan-service 자체 DB)
--
-- corporate_loans : 상장사가 재무제표·평판 기반 신용점수로 심사받는 무담보 신용대출.
--   신용점수/등급은 신청 시점 스냅샷으로 보존한다(이후 재무·평판 변동과 무관한 이력 재현성).
--   상태머신: REQUESTED → APPROVED → DISBURSED → REPAID, 분기 REQUESTED → REJECTED.

CREATE TABLE IF NOT EXISTS corporate_loans (
    id           BIGSERIAL      PRIMARY KEY,
    stock_code   VARCHAR(6)     NOT NULL,   -- 6자리 종목코드
    corp_name    VARCHAR(255)   NOT NULL,   -- 회사명(스냅샷)
    principal    NUMERIC(19, 2) NOT NULL,   -- 대출 원금
    fee          NUMERIC(19, 2) NOT NULL,   -- 수수료(이자)
    outstanding  NUMERIC(19, 2) NOT NULL,   -- 미상환 잔액 (실행 시 principal+fee, 상환 시 감소)
    term_days    INT            NOT NULL,   -- 대출 기간(일)
    credit_score INT            NOT NULL,   -- 신용점수 스냅샷(0~100)
    credit_grade VARCHAR(1)     NOT NULL,   -- 신용등급 스냅샷(A~E)
    status       VARCHAR(20)    NOT NULL,   -- REQUESTED/APPROVED/DISBURSED/REPAID/REJECTED
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_corp_loan_principal   CHECK (principal > 0),
    CONSTRAINT chk_corp_loan_term_days   CHECK (term_days > 0),
    CONSTRAINT chk_corp_loan_credit_score CHECK (credit_score BETWEEN 0 AND 100),
    CONSTRAINT chk_corp_loan_status CHECK (status IN
        ('REQUESTED','APPROVED','DISBURSED','REPAID','REJECTED'))
);

-- 종목별 대출 이력 조회(최신순) + 전체 최신순 목록 조회
CREATE INDEX IF NOT EXISTS idx_corporate_loans_stock_code
    ON corporate_loans (stock_code, id DESC);
