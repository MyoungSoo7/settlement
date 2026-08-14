-- V20260809031000: 수신 상품 — 정기예금 서브원장 (account.banking.timedeposit)
--
-- [왜 계정계 안에 서브원장을 두는가]
-- account_entries 는 전사 GL 이라 "차변 1 / 대변 1 / 금액 1" 이라는 전표 형태만 안다. 정기예금의
-- 약정이율·중도해지이율·복리방식·만기일 같은 *계약 조건* 은 전표에 담을 자리가 없고, 담아서도 안 된다
-- (GL 은 사건의 금액만 기록한다). 그래서 계약 상태는 이 서브원장이 소유하고, 상태가 바뀌는 순간의
-- 금액 이동만 GL 로 mirror 한다 — loan·settlement 이 각자 서브원장을 갖는 것과 동일한 구조다.
--
-- [왜 이자 컬럼이 하나뿐인가]
-- 이자는 주기 accrual 을 두지 않고 만기·중도해지 *시점에 한 번* 확정한다(TimeDepositInterest, ACT/365).
-- 미확정 이자를 매일 적립하는 테이블을 두면 스케줄러 누락·중복 실행이 곧바로 이자 누수·이중지급이 되고,
-- 그 오차가 GL 수신부채와 벌어지는 순간 대사가 불가능해진다. 확정 시점이 하나뿐이면 그 사고가 없다.
-- 따라서 settled_interest / payout_amount / closed_on 은 ACTIVE 동안 전부 NULL 이고, CLOSED 가 되는
-- 그 한 번의 전이에서 동시에 채워진다 — chk_time_deposit_closed_fields 가 이 "전부 NULL 또는 전부 NOT NULL"
-- 을 강제해, 절반만 닫힌 행(이자는 확정됐는데 해지일이 없는 등)이 물리적으로 존재하지 못하게 한다.
--
-- [CHECK 는 도메인 불변식의 사본이다]
-- TimeDeposit.open() 이 검증하는 조건(원금>0, 이율 [0,1), 기간>0, 만기일=개설일+기간)을 DB 에도 같은
-- 모양으로 심는다. 애플리케이션을 우회한 수기 INSERT·백필 스크립트가 도메인이 절대 만들지 않는 값을
-- 심어 놓으면, 그 행을 읽어 계산한 이자가 조용히 틀린 채 GL 로 나간다. 이중 관리 비용보다 그쪽이 비싸다.
--
-- [금액·이율 타입] 금액은 GL 과 동일한 numeric(19,2). 이율은 numeric(9,6) — 연 0.031500(=3.15%) 처럼
-- bp 미만까지 무손실로 담기며, double 은 어디에도 쓰지 않는다(이진 부동소수 오차 = 원 단위 드리프트).

CREATE TABLE IF NOT EXISTS time_deposits (
    id                     BIGSERIAL      PRIMARY KEY,
    depositor_id           VARCHAR(64)    NOT NULL,   -- OwnerType.DEPOSITOR 의 ownerId (userId 숫자 문자열)
    product_name           VARCHAR(100)   NOT NULL,
    principal              NUMERIC(19, 2) NOT NULL,   -- 예치 원금
    annual_rate            NUMERIC(9, 6)  NOT NULL,   -- 약정이율(연리) — 만기해지 시 적용
    early_termination_rate NUMERIC(9, 6)  NOT NULL,   -- 중도해지이율(연리) — 중도해지 시 적용
    compounding            VARCHAR(20)    NOT NULL,   -- Compounding (SIMPLE | MONTHLY_COMPOUND)
    term_months            INTEGER        NOT NULL,   -- 예치 기간(개월)
    opened_on              DATE           NOT NULL,
    maturity_date          DATE           NOT NULL,   -- = opened_on + term_months (LocalDate 월말 보정 규칙)
    status                 VARCHAR(20)    NOT NULL,   -- TimeDepositStatus (ACTIVE | CLOSED)
    closed_on              DATE,                      -- 해지 시에만
    settled_interest       NUMERIC(19, 2),            -- 확정이자(원 단위 HALF_UP) — 해지 시에만
    payout_amount          NUMERIC(19, 2),            -- = principal + settled_interest — 해지 시에만

    CONSTRAINT chk_time_deposit_principal    CHECK (principal > 0),
    CONSTRAINT chk_time_deposit_annual_rate  CHECK (annual_rate >= 0 AND annual_rate < 1),
    CONSTRAINT chk_time_deposit_early_rate   CHECK (early_termination_rate >= 0 AND early_termination_rate < 1),
    CONSTRAINT chk_time_deposit_term_months  CHECK (term_months > 0),
    CONSTRAINT chk_time_deposit_compounding  CHECK (compounding IN ('SIMPLE', 'MONTHLY_COMPOUND')),
    CONSTRAINT chk_time_deposit_status       CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT chk_time_deposit_maturity     CHECK (maturity_date > opened_on),
    -- 예금주 식별자 형식 — account_entries.chk_account_entry_owner_id_format 의 DEPOSITOR 규약과 동일
    CONSTRAINT chk_time_deposit_depositor_id CHECK (depositor_id ~ '^[0-9]+$'),
    -- 해지 3종 세트는 함께 채워지거나 함께 비어 있어야 한다 (절반만 닫힌 행 금지)
    CONSTRAINT chk_time_deposit_closed_fields CHECK (
        (status = 'ACTIVE'
             AND closed_on IS NULL AND settled_interest IS NULL AND payout_amount IS NULL)
     OR (status = 'CLOSED'
             AND closed_on IS NOT NULL AND settled_interest IS NOT NULL AND payout_amount IS NOT NULL
             AND closed_on >= opened_on
             AND settled_interest >= 0
             AND payout_amount = principal + settled_interest)
    )
);

-- 본인 계좌 목록 조회 핫패스 (최신 개설 순) — listMine 이 유일한 목록 경로다
CREATE INDEX IF NOT EXISTS idx_time_deposits_depositor
    ON time_deposits (depositor_id, id DESC);

COMMENT ON TABLE time_deposits IS
    '정기예금 계약 서브원장 — 정본은 도메인 애그리거트 TimeDeposit(account.banking.timedeposit.domain). 금액 이동은 AccountEntry.timeDeposit*() 3종 팩토리로 account_entries 에 인프로세스 mirror 되며(refId=TD-{id}), 이 테이블이 계약 조건·상태의 단일 출처다.';
COMMENT ON COLUMN time_deposits.depositor_id IS
    '예금주 — OwnerType.DEPOSITOR 의 ownerId 규약(userId 숫자 문자열)을 그대로 따른다. GL 전표의 owner_id 와 같은 값이라 두 원장을 이 키로 대사한다.';
COMMENT ON COLUMN time_deposits.settled_interest IS
    '해지 시 1회 확정된 이자(원 단위 HALF_UP, ACT/365). 정본 산식은 TimeDepositInterest — 주기 accrual 은 두지 않는다. 0 이면 이자 전표(TIME_DEPOSIT_INTEREST)는 전기되지 않는다(GL 은 양수 금액만 허용).';
COMMENT ON COLUMN time_deposits.payout_amount IS
    '해지 지급액 = principal + settled_interest. TIME_DEPOSIT_CLOSED 전표의 금액과 반드시 일치한다 — 불일치는 곧 수신부채가 0 으로 닫히지 않는다는 뜻이다.';
COMMENT ON CONSTRAINT chk_time_deposit_closed_fields ON time_deposits IS
    '해지 원자성 — closed_on/settled_interest/payout_amount 는 status 전이와 함께 전부 채워지거나 전부 비어야 한다. 절반만 닫힌 행은 GL 전표와 서브원장이 어긋난 상태를 물리적으로 표현하게 되므로 금지.';
