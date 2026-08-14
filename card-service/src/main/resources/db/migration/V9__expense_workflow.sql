-- V9: 사후 지출관리 워크플로 (Phase 2 AC4)
--
-- expense_reports: 매입 확정 이벤트 소비로 자동 생성되는 지출보고서.
--   report_id  : 자연키
--   capture_id : 매입번호 = 멱등 키 (매입당 보고서 1개)
-- department_budgets: 부서별 월 예산 및 소진액 추적.

CREATE TABLE expense_reports (
    id                BIGSERIAL       PRIMARY KEY,
    report_id         VARCHAR(64)     NOT NULL,                -- 자연키
    capture_id        VARCHAR(64)     NOT NULL,                -- 매입번호 = 멱등 키
    authorization_id  VARCHAR(64)     NOT NULL,
    card_id           BIGINT          NOT NULL REFERENCES cards(id),
    card_account_id   BIGINT          NOT NULL REFERENCES card_accounts(id),
    organization_id   BIGINT          NOT NULL,
    department_id     VARCHAR(64),                             -- 부서 ID (예산 소진율 집계 기준)
    holder_user_id    BIGINT          NOT NULL,
    amount            NUMERIC(19,2)   NOT NULL,
    merchant_name     VARCHAR(200),
    status            VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    expense_category  VARCHAR(64),                             -- 경비 카테고리
    receipt_url       VARCHAR(500),                            -- 영수증 URL
    memo              TEXT,
    reviewed_by       BIGINT,                                  -- 승인/반려자 userId
    reject_reason     VARCHAR(500),                            -- 반려 사유
    captured_at       TIMESTAMPTZ     NOT NULL,
    submitted_at      TIMESTAMPTZ,
    reviewed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_expense_report_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_expense_amount_positive
        CHECK (amount > 0)
);

-- 자연키·멱등 키
CREATE UNIQUE INDEX uq_expense_report_id ON expense_reports (report_id);
-- 매입당 보고서 1개 — 같은 매입으로 두 번 생성 불가
CREATE UNIQUE INDEX uq_expense_capture_id ON expense_reports (capture_id);
-- 조직별 상태 조회
CREATE INDEX idx_expense_org_status ON expense_reports (organization_id, status);
-- 소지자별 상태 조회 (제출 대기 목록)
CREATE INDEX idx_expense_holder_status ON expense_reports (holder_user_id, status);

CREATE TABLE department_budgets (
    id                BIGSERIAL       PRIMARY KEY,
    organization_id   BIGINT          NOT NULL,
    department_id     VARCHAR(64)     NOT NULL,
    budget_year       INT             NOT NULL,
    budget_month      INT             NOT NULL CHECK (budget_month BETWEEN 1 AND 12),
    total_budget      NUMERIC(19,2)   NOT NULL DEFAULT 0,
    approved_amount   NUMERIC(19,2)   NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_budget_non_negative       CHECK (total_budget >= 0),
    CONSTRAINT chk_approved_non_negative     CHECK (approved_amount >= 0),
    CONSTRAINT uq_dept_budget_period
        UNIQUE (organization_id, department_id, budget_year, budget_month)
);

-- 소진율 조회용
CREATE INDEX idx_dept_budget_org_dept ON department_budgets (organization_id, department_id);

COMMENT ON TABLE expense_reports IS '사후 지출보고서. capture_id(VAN 매입번호)가 멱등 키 — 같은 매입에 대해 보고서가 중복 생성되지 않는다.';
COMMENT ON COLUMN expense_reports.report_id IS '지출보고서 고유 ID — 워크플로 전이 API 의 자연키.';
COMMENT ON COLUMN expense_reports.capture_id IS 'VAN 매입번호 — lemuel.card.captured 이벤트의 captureId 와 1:1 매핑.';
COMMENT ON TABLE department_budgets IS '부서별 월 예산 및 소진액. 지출보고서 승인 시 approved_amount 증가.';
