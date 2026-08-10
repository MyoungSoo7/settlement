-- V20260809031500: 수신상품 적금(積金) 서브원장 — installment_savings / savings_installments
--
-- 왜 서브원장이 따로 필요한가:
--   GL(account_entries)은 "얼마가 어느 계정으로 움직였는가"만 안다. 적금의 확정이자는 회차마다
--   예치 시작일이 달라 (납입액 × 이율 × 예치일수 / 365) 를 회차별로 더해야 나오는데, 그 계산에
--   필요한 사실(회차 번호·실제 납입일·기일·연체일수)은 전표에 담기지 않는다. GL 전표만 두고
--   이자를 되짚으려면 refId 문자열(SV-{savingsId}-{round})을 파싱해 occurred_at 을 납입일로
--   가정해야 하는데, 그건 회계 사실을 계산 입력으로 재해석하는 것이라 감사 추적이 끊긴다.
--   따라서 적금의 계약·회차는 여기 서브원장이 정본이고, GL 은 그 결과를 기표만 한다.
--
-- 연체 설계(중요): 연체는 만기일을 밀지 않는다. 늦게 낸 회차는 paid_on 이 늦어져 그 회차의
--   예치일수가 줄고, 결과적으로 그 회차의 이자만 감소한다. 만기이연 방식을 택하지 않은 이유는
--   (1) 계약 종료일이 납입 이력에 따라 변하면 만기 통지·정산 배치가 계약별로 갈라지고,
--   (2) 만기를 밀면 정상 납입한 다른 회차의 이자까지 늘어 "늦게 낸 사람이 이득"이 되기 때문이다.
--   overdue_days 는 그래서 이자 계산의 입력이 아니라 기록·감사용 파생값이다(정본은 paid_on).
--
-- 이자 정본은 코드다: 계산은 InstallmentSavingsInterest(ACT/365 단리, 합계에 1회 HALF_UP 원 단위
--   반올림)가 수행하고, 확정된 값만 settled_interest/payout_amount 로 저장한다. SQL 에 이자식을
--   복제하지 않는다 — 두 벌이 되는 순간 조용한 드리프트가 시작된다.

CREATE TABLE IF NOT EXISTS installment_savings (
    id                     BIGSERIAL      PRIMARY KEY,
    depositor_id           VARCHAR(64)    NOT NULL,   -- OwnerType.DEPOSITOR 의 ownerId = userId 숫자 문자열
    product_name           VARCHAR(100)   NOT NULL,
    savings_type           VARCHAR(20)    NOT NULL,   -- SavingsType: FIXED(정액적립식) / FLEXIBLE(자유적립식)
    monthly_amount         NUMERIC(19, 2),            -- FIXED 전용 월 약정액 (FLEXIBLE 은 NULL)
    payment_limit          NUMERIC(19, 2),            -- FLEXIBLE 전용 회차 한도 (FIXED 는 NULL, FLEXIBLE 도 선택)
    annual_rate            NUMERIC(9, 6)  NOT NULL,   -- 약정 연이율 (0.035000 = 연 3.5%)
    early_termination_rate NUMERIC(9, 6)  NOT NULL,   -- 중도해지 연이율
    term_months            INTEGER        NOT NULL,
    opened_on              DATE           NOT NULL,
    maturity_date          DATE           NOT NULL,   -- = opened_on + term_months (도메인이 유도)
    status                 VARCHAR(20)    NOT NULL,   -- SavingsStatus: ACTIVE / CLOSED
    closed_on              DATE,
    settled_interest       NUMERIC(19, 2),            -- 해지 시점에 한 번 확정 (주기 accrual 없음)
    payout_amount          NUMERIC(19, 2),            -- Σ 납입액 + settled_interest = savingsClosed 전표 금액
    created_at             TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_installment_savings_type
        CHECK (savings_type IN ('FIXED', 'FLEXIBLE')),
    CONSTRAINT chk_installment_savings_status
        CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT chk_installment_savings_term
        CHECK (term_months > 0),
    -- 이율은 [0, 1) — 퍼센트(3.5)를 소수(0.035) 자리에 넣는 실수를 스키마에서 막는다.
    CONSTRAINT chk_installment_savings_rates
        CHECK (annual_rate >= 0 AND annual_rate < 1
               AND early_termination_rate >= 0 AND early_termination_rate < 1),
    CONSTRAINT chk_installment_savings_maturity
        CHECK (maturity_date > opened_on),
    -- 상품 유형과 금액 필드의 정합 — "쓰이지 않는 값이 조용히 채워져 있는" 상태를 원천 차단한다.
    -- 그 상태가 남으면 나중에 잘못된 검증 분기를 타고, 유형 변경 백필에서 유령 한도가 되살아난다.
    CONSTRAINT chk_installment_savings_type_amounts
        CHECK ((savings_type = 'FIXED'
                    AND monthly_amount IS NOT NULL AND monthly_amount > 0
                    AND payment_limit IS NULL)
               OR (savings_type = 'FLEXIBLE'
                    AND monthly_amount IS NULL
                    AND (payment_limit IS NULL OR payment_limit > 0))),
    -- 해지 상태 정합 — CLOSED 면 해지일·확정이자·지급액이 모두 있어야 하고, ACTIVE 면 모두 없어야 한다.
    -- 반쪽 상태(해지일만 있고 지급액 없음)는 원리금 미지급 사고로 직결된다.
    CONSTRAINT chk_installment_savings_closed_fields
        CHECK ((status = 'ACTIVE'
                    AND closed_on IS NULL AND settled_interest IS NULL AND payout_amount IS NULL)
               OR (status = 'CLOSED'
                    AND closed_on IS NOT NULL
                    AND settled_interest IS NOT NULL AND settled_interest >= 0
                    AND payout_amount IS NOT NULL AND payout_amount >= 0
                    AND closed_on >= opened_on))
);

CREATE INDEX IF NOT EXISTS idx_installment_savings_depositor
    ON installment_savings (depositor_id, opened_on DESC, id DESC);

COMMENT ON TABLE installment_savings IS
    '적금 계약 서브원장 — 계약 조건·상태·확정이자의 정본. GL(account_entries)은 SAVINGS_* 전표로 결과만 기표한다.';
COMMENT ON COLUMN installment_savings.depositor_id IS
    'OwnerType.DEPOSITOR 의 ownerId — userId 숫자 문자열. JWT 주체에서만 파생된다(IDOR 방지).';
COMMENT ON COLUMN installment_savings.settled_interest IS
    '해지 시 1회 확정된 이자(원 단위). ACT/365 단리 · 회차별 일수 가중 합계에 HALF_UP 1회 반올림 — 정본 계산은 InstallmentSavingsInterest.';
COMMENT ON COLUMN installment_savings.payout_amount IS
    'Σ 회차 납입액 + settled_interest. savingsClosed 전표(DR INSTALLMENT_SAVINGS_LIABILITY / CR CASH)의 금액과 같아야 한다.';
COMMENT ON CONSTRAINT chk_installment_savings_type_amounts ON installment_savings IS
    'FIXED 는 monthly_amount 필수·payment_limit 금지, FLEXIBLE 은 그 반대 — InstallmentSavings.open 의 불변식을 스키마에도 못 박는다.';

CREATE TABLE IF NOT EXISTS savings_installments (
    id           BIGSERIAL      PRIMARY KEY,
    savings_id   BIGINT         NOT NULL,
    round_no     INTEGER        NOT NULL,   -- 회차. 컬럼명이 round 가 아닌 이유는 SQL 함수명 충돌 회피
    amount       NUMERIC(19, 2) NOT NULL,
    due_date     DATE           NOT NULL,   -- = opened_on + (round_no - 1) 개월
    paid_on      DATE           NOT NULL,   -- 실제 납입일 — 이자 기산일의 정본
    overdue_days INTEGER        NOT NULL DEFAULT 0,   -- paid_on > due_date 인 일수(파생·기록용)
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_savings_installment_savings
        FOREIGN KEY (savings_id) REFERENCES installment_savings (id),
    CONSTRAINT chk_savings_installment_round
        CHECK (round_no >= 1),
    CONSTRAINT chk_savings_installment_amount
        CHECK (amount > 0),
    CONSTRAINT chk_savings_installment_overdue
        CHECK (overdue_days >= 0),
    -- 회차 멱등의 최종 방어선. 도메인도 중복 회차를 거절하지만, 동시 요청 둘이 같은 애그리거트를
    -- 각자 읽어 각자 통과하는 창은 코드로 닫히지 않는다 — 그 창을 여기서 닫는다.
    -- GL 쪽 자연키(SV-{savingsId}-{round})의 UNIQUE 와 짝을 이룬다.
    CONSTRAINT uq_savings_installment_round
        UNIQUE (savings_id, round_no)
);

CREATE INDEX IF NOT EXISTS idx_savings_installments_savings
    ON savings_installments (savings_id, round_no);

COMMENT ON TABLE savings_installments IS
    '적금 회차 납입 이력(append-only) — 납입된 회차만 행으로 존재한다. 미납 회차는 행이 없다.';
COMMENT ON COLUMN savings_installments.paid_on IS
    '실제 납입일. 이 회차의 예치일수(= paid_on → 만기일 또는 중도해지일)를 정하는 유일한 입력이다.';
COMMENT ON COLUMN savings_installments.overdue_days IS
    '연체일수(기록·감사용). 이자 계산에 직접 쓰이지 않는다 — 연체 효과는 paid_on 이 늦어져 예치일수가 줄어드는 것으로 이미 반영된다. 만기일은 밀지 않는다.';
COMMENT ON CONSTRAINT uq_savings_installment_round ON savings_installments IS
    '(savings_id, round_no) 유일 — 같은 회차 이중 납입 차단. 도메인 검증의 동시성 사각을 메우는 최종 방어선.';
