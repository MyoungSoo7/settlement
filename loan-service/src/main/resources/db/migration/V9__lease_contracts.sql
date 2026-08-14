-- V9: 리스·할부 물건금융 계약 (loan-service 자체 DB)
--
-- lease_contracts : 금융리스·운용리스·할부금융 계약. 캐피탈(여신전문금융) 물건금융의 계약 단위.
--
-- ★ 회차표(스케줄)를 저장하지 않는다 — 스케줄은 아래 산정 입력값의 결정적 순수 함수
--   (LeaseSchedule.of)이므로 입력을 보존하면 언제든 같은 표가 재현된다. 표를 따로 저장하면
--   입력과 표가 어긋나는 두 번째 진실원이 생기고, 회차 수백 행이 계약마다 늘어난다.
--   반대로 산정 입력값은 계약 체결 시점 스냅샷이라 사후 변경하지 않는다(변경은 재계약).
--
-- 차주는 담보대출(secured_loans)과 같은 이유로 평탄화 보관한다 — 신청 시점 스냅샷.

CREATE TABLE IF NOT EXISTS lease_contracts (
    id                       BIGSERIAL      PRIMARY KEY,

    -- 차주 스냅샷
    borrower_type            VARCHAR(20)    NOT NULL,   -- INDIVIDUAL/CORPORATE
    borrower_user_id         BIGINT         NOT NULL,
    borrower_name            VARCHAR(255)   NOT NULL,
    borrower_registration_no VARCHAR(10),

    -- 상품·물건
    finance_type             VARCHAR(20)    NOT NULL,   -- FINANCE_LEASE/OPERATING_LEASE/INSTALLMENT
    asset_description        VARCHAR(255)   NOT NULL,   -- 리스 물건 표시

    -- 스케줄 산정 입력값 (계약 시점 확정 — 이 값들로 회차표를 재현한다)
    acquisition_cost         NUMERIC(19, 2) NOT NULL,   -- 취득원가
    down_payment             NUMERIC(19, 2) NOT NULL,   -- 선수금(반환 없음)
    deposit                  NUMERIC(19, 2) NOT NULL,   -- 보증금(만기 반환)
    residual_value           NUMERIC(19, 2) NOT NULL,   -- 잔존가치(만기 인수가·반환 장부가)
    term_months              INT            NOT NULL,
    annual_rate_percent      NUMERIC(9, 4)  NOT NULL,

    -- 진행 상태
    status                   VARCHAR(20)    NOT NULL,
    paid_installments        INT            NOT NULL DEFAULT 0,

    applied_at               TIMESTAMPTZ    NOT NULL,
    activated_at             TIMESTAMPTZ,               -- 물건 인도 시각(개시)
    closed_at                TIMESTAMPTZ,               -- 만기·중도해지 종료 시각
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_lease_cost      CHECK (acquisition_cost > 0),
    CONSTRAINT chk_lease_term      CHECK (term_months >= 1),
    CONSTRAINT chk_lease_rate      CHECK (annual_rate_percent >= 0),
    CONSTRAINT chk_lease_upfront   CHECK (down_payment >= 0 AND deposit >= 0 AND residual_value >= 0),
    -- 회수할 리스 원금이 남아야 하고, 잔존가치는 그보다 작아야 한다(도메인 불변식의 DB 최종 방어)
    CONSTRAINT chk_lease_financed  CHECK (acquisition_cost - down_payment - deposit > 0),
    CONSTRAINT chk_lease_residual  CHECK (residual_value < acquisition_cost - down_payment - deposit),
    -- 할부는 잔존가치를 둘 수 없다(전액 회수 상품)
    CONSTRAINT chk_lease_installment_residual
        CHECK (finance_type <> 'INSTALLMENT' OR residual_value = 0),
    -- 운용리스는 잔존가치가 있어야 한다(반환 전제)
    CONSTRAINT chk_lease_operating_residual
        CHECK (finance_type <> 'OPERATING_LEASE' OR residual_value > 0),
    CONSTRAINT chk_lease_paid      CHECK (paid_installments BETWEEN 0 AND term_months),
    CONSTRAINT chk_lease_type      CHECK (finance_type IN
        ('FINANCE_LEASE','OPERATING_LEASE','INSTALLMENT')),
    CONSTRAINT chk_lease_status    CHECK (status IN
        ('APPLIED','APPROVED','ACTIVE','OVERDUE','DEFAULTED','MATURED','EARLY_TERMINATED',
         'REJECTED','CANCELLED'))
);

-- 차주 본인 계약 최신순 조회(소유권 스코핑 핫패스)
CREATE INDEX IF NOT EXISTS idx_lease_contracts_borrower
    ON lease_contracts (borrower_user_id, id DESC);

-- 청구·연체 판정 배치 대상(살아 있는 계약)만 좁히는 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_lease_contracts_billable
    ON lease_contracts (status, id)
    WHERE status IN ('ACTIVE', 'OVERDUE');
