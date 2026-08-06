-- V1: insurance-service 자체 DB(lemuel_insurance) — GA 보험대리점 플랫폼 핵심 도메인 테이블
--
-- DB-per-service: 타 서비스 FK 전혀 없음, cross-DB JOIN 전혀 없음.
-- 연계는 Kafka 이벤트 + 내부 REST 로만.
--
-- 포함 도메인:
--   1. insurance_products — 상품 카탈로그 (D3: 자체 소유, product_code VARCHAR 만 두는 안 기각)
--   2. consultations      — 상담 칸반 (D2: 5개 상태, 5개 전이)
--   3. insured_person_pii — 피보험자 PII (별도 테이블 + 암호화)
--   4. contractor_pii     — 계약자 PII (별도 테이블 + 암호화)
--   5. insurance_applications — 청약 언더라이팅
--   6. insurance_policies — 계약 SoR (D7: 7개 전이, 5개 상태)
--   7. policy_coverages   — 보장
--   8. servicing_changes  — 유지·변경 append-only 이력
--   9. commission_schedules — 수수료 스케줄 (D4: 12회, D5: nullable 컬럼)

-- ─────────────────────────────────────────────
-- 1. 상품 카탈로그 (D3: 자체 소유 필수)
-- ─────────────────────────────────────────────
CREATE TABLE insurance_products (
    id                              BIGSERIAL      PRIMARY KEY,
    product_code                    VARCHAR(32)    NOT NULL,
    product_name                    VARCHAR(200)   NOT NULL,
    product_type                    VARCHAR(50)    NOT NULL,   -- LIFE, HEALTH, AUTO, FIRE, TRAVEL, PENSION
    annual_premium                  NUMERIC(19,2)  NOT NULL,
    coverage_amount                 NUMERIC(19,2)  NOT NULL,
    first_year_commission_rate      NUMERIC(7,6)   NOT NULL,  -- 초년도 수수료율 (예: 0.035000 = 3.5%)
    subsequent_year_commission_rate NUMERIC(7,6)   NOT NULL,  -- 차년도 수수료율
    active                          BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version                         BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT chk_insurance_product_type
        CHECK (product_type IN ('LIFE', 'HEALTH', 'AUTO', 'FIRE', 'TRAVEL', 'PENSION')),
    CONSTRAINT chk_insurance_product_premium_positive CHECK (annual_premium > 0),
    CONSTRAINT chk_insurance_product_coverage_positive CHECK (coverage_amount > 0),
    CONSTRAINT chk_insurance_product_first_year_rate_range
        CHECK (first_year_commission_rate >= 0 AND first_year_commission_rate <= 1),
    CONSTRAINT chk_insurance_product_subsequent_rate_range
        CHECK (subsequent_year_commission_rate >= 0 AND subsequent_year_commission_rate <= 1)
);

-- 상품코드는 자연키 — 3단 멱등성 L3 역할
CREATE UNIQUE INDEX uq_insurance_product_code ON insurance_products (product_code);
CREATE INDEX idx_insurance_product_type ON insurance_products (product_type);
CREATE INDEX idx_insurance_product_active ON insurance_products (active) WHERE active = TRUE;

-- ─────────────────────────────────────────────
-- 2. 상담 칸반 (D2: NEW→ASSIGNED→IN_PROGRESS→COMPLETED|LOST, 5개 상태, 5개 전이만)
-- ─────────────────────────────────────────────
CREATE TABLE consultations (
    id               BIGSERIAL     PRIMARY KEY,
    consultation_id  UUID          NOT NULL,
    fc_id            VARCHAR(64)   NOT NULL,    -- 배정된 설계사
    lead_name        VARCHAR(100)  NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    notes            TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version          BIGINT        NOT NULL DEFAULT 0,

    -- D2: 허용 상태 5개뿐 (NEW, ASSIGNED, IN_PROGRESS, COMPLETED, LOST)
    CONSTRAINT chk_consultation_status
        CHECK (status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'LOST'))
);

CREATE UNIQUE INDEX uq_consultation_id ON consultations (consultation_id);
CREATE INDEX idx_consultation_fc ON consultations (fc_id);
CREATE INDEX idx_consultation_status ON consultations (status);

-- ─────────────────────────────────────────────
-- 3. 피보험자 PII (별도 테이블 분리 + 암호화 — AES-256)
--    InsurancePiiEncryptionConverter 가 application_id 단위로 암호화한다.
-- ─────────────────────────────────────────────
CREATE TABLE insured_person_pii (
    id               BIGSERIAL  PRIMARY KEY,
    application_id   UUID       NOT NULL,   -- insurance_applications.application_id 비검증 참조
    encrypted_rrn    TEXT       NOT NULL,   -- AES-GCM enc:v1: 접두 + Base64(IV||ciphertext+tag)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_insured_pii_application ON insured_person_pii (application_id);

-- ─────────────────────────────────────────────
-- 4. 계약자 PII (별도 테이블 분리 + 암호화 — AES-256)
-- ─────────────────────────────────────────────
CREATE TABLE contractor_pii (
    id               BIGSERIAL  PRIMARY KEY,
    application_id   UUID       NOT NULL,   -- insurance_applications.application_id 비검증 참조
    encrypted_phone  TEXT       NOT NULL,   -- AES-GCM enc:v1: 접두 + Base64(IV||ciphertext+tag)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_contractor_pii_application ON contractor_pii (application_id);

-- ─────────────────────────────────────────────
-- 5. 청약 (언더라이팅 상태머신: SUBMITTED→UNDER_REVIEW→APPROVED|REJECTED)
-- ─────────────────────────────────────────────
CREATE TABLE insurance_applications (
    id               BIGSERIAL     PRIMARY KEY,
    application_id   UUID          NOT NULL,
    consultation_id  UUID,                     -- 상담 경유 유입 시만 (nullable)
    product_code     VARCHAR(32)   NOT NULL,   -- insurance_products.product_code 비검증 참조
    fc_id            VARCHAR(64)   NOT NULL,
    insured_name     VARCHAR(100)  NOT NULL,
    contractor_name  VARCHAR(100)  NOT NULL,
    desired_coverage NUMERIC(19,2) NOT NULL,
    desired_premium  NUMERIC(19,2) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'SUBMITTED',
    reject_reason    VARCHAR(500),
    submitted_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    reviewed_at      TIMESTAMPTZ,
    decided_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version          BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_application_status
        CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_application_desired_coverage_positive CHECK (desired_coverage > 0),
    CONSTRAINT chk_application_desired_premium_positive CHECK (desired_premium > 0)
);

CREATE UNIQUE INDEX uq_application_id ON insurance_applications (application_id);
CREATE INDEX idx_application_fc ON insurance_applications (fc_id);
CREATE INDEX idx_application_status ON insurance_applications (status);
CREATE INDEX idx_application_consultation ON insurance_applications (consultation_id);

-- ─────────────────────────────────────────────
-- 6. 계약 (D7: Policy SoR — 5개 상태, 허용 전이 7개, InvalidPolicyTransitionException)
-- ─────────────────────────────────────────────
CREATE TABLE insurance_policies (
    id                           BIGSERIAL     PRIMARY KEY,
    policy_id                    UUID          NOT NULL,
    policy_number                VARCHAR(64)   NOT NULL,   -- 증권번호 자연키 — 3단 멱등성 L3
    application_id               UUID          NOT NULL,   -- insurance_applications.application_id
    fc_id                        VARCHAR(64)   NOT NULL,
    product_code                 VARCHAR(32)   NOT NULL,

    -- D7: 5개 상태 — ACTIVE, LAPSED, SURRENDERED, EXPIRED, CANCELLED
    status                       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',

    -- 금액 (BigDecimal — double/float 금지, DATA-STANDARD N5: 이벤트에서는 toPlainString())
    premium_amount               NUMERIC(19,2) NOT NULL,
    coverage_amount              NUMERIC(19,2) NOT NULL,
    payment_cycle_months         INTEGER       NOT NULL DEFAULT 1,  -- 납입 주기(개월)

    -- 일자
    effective_date               DATE          NOT NULL,   -- 계약 효력일 — D6 환수 기산점, D7 청약철회 기산점
    maturity_date                DATE,                     -- 만기일 — 도래 시 ACTIVE→EXPIRED (D7)
    lapsed_at                    DATE,                     -- 실효일 — 부활가능기간(REINSTATEMENT_WINDOW_MONTHS) 기산점 (D7)

    -- D7: 연속 납입 실패 횟수 — 2회 연속에서만 ACTIVE→LAPSED (1회로는 전이 없음)
    consecutive_premium_failures INTEGER       NOT NULL DEFAULT 0,

    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version                      BIGINT        NOT NULL DEFAULT 0,

    -- D7: 상태머신 제약 — 허용 전이 7개 외에는 InvalidPolicyTransitionException (앱이 강제)
    CONSTRAINT chk_policy_status
        CHECK (status IN ('ACTIVE', 'LAPSED', 'SURRENDERED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_policy_failures_non_negative
        CHECK (consecutive_premium_failures >= 0),
    CONSTRAINT chk_policy_premium_positive CHECK (premium_amount > 0),
    CONSTRAINT chk_policy_coverage_positive CHECK (coverage_amount > 0)
);

CREATE UNIQUE INDEX uq_policy_id ON insurance_policies (policy_id);
CREATE UNIQUE INDEX uq_policy_number ON insurance_policies (policy_number);  -- 자연키 / 3단 멱등성 L3
CREATE INDEX idx_policy_fc ON insurance_policies (fc_id);
CREATE INDEX idx_policy_status ON insurance_policies (status);
CREATE INDEX idx_policy_effective_date ON insurance_policies (effective_date);
-- ACTIVE 만 만기일을 조회하면 됨 (배치 스케줄러)
CREATE INDEX idx_policy_maturity_active ON insurance_policies (maturity_date)
    WHERE status = 'ACTIVE';
-- LAPSED 에서 부활가능기간 도과 스캔
CREATE INDEX idx_policy_lapsed_at ON insurance_policies (lapsed_at)
    WHERE status = 'LAPSED';

-- ─────────────────────────────────────────────
-- 7. 보장 (Coverage — policy 의 보장 내역)
-- ─────────────────────────────────────────────
CREATE TABLE policy_coverages (
    id               BIGSERIAL     PRIMARY KEY,
    policy_id        UUID          NOT NULL,   -- insurance_policies.policy_id 비검증 참조
    coverage_code    VARCHAR(50)   NOT NULL,
    coverage_name    VARCHAR(200)  NOT NULL,
    coverage_amount  NUMERIC(19,2) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_coverage_amount_positive CHECK (coverage_amount > 0)
);

CREATE INDEX idx_coverage_policy ON policy_coverages (policy_id);
CREATE UNIQUE INDEX uq_coverage_policy_code ON policy_coverages (policy_id, coverage_code);

-- ─────────────────────────────────────────────
-- 8. 유지·변경 이력 (Servicing — append-only, UPDATE 로 덮어쓰지 않는다)
-- ─────────────────────────────────────────────
CREATE TABLE servicing_changes (
    id           BIGSERIAL     PRIMARY KEY,
    change_id    UUID          NOT NULL,
    policy_id    UUID          NOT NULL,   -- insurance_policies.policy_id 비검증 참조
    change_type  VARCHAR(50)   NOT NULL,   -- PREMIUM_FAILURE, REVIVAL, SURRENDER, BENEFICIARY_CHANGE, ADDRESS_CHANGE, …
    previous_value JSONB,
    new_value    JSONB,
    notes        TEXT,
    changed_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
    -- ★ updated_at / version 없음 — 절대 UPDATE 없음. append-only 불변 이력.
);

CREATE UNIQUE INDEX uq_servicing_change_id ON servicing_changes (change_id);
CREATE INDEX idx_servicing_change_policy ON servicing_changes (policy_id, changed_at DESC);
CREATE INDEX idx_servicing_change_type ON servicing_changes (change_type, changed_at DESC);

-- append-only 강제 (UPDATE·DELETE 거부)
CREATE OR REPLACE FUNCTION servicing_changes_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'servicing_changes 는 append-only 입니다: % 연산 불가 (변경이력 변조 차단)', TG_OP;
END;
$$;
CREATE TRIGGER trg_servicing_changes_append_only
    BEFORE UPDATE OR DELETE ON servicing_changes
    FOR EACH ROW EXECUTE FUNCTION servicing_changes_block_modify();

-- ─────────────────────────────────────────────
-- 9. 수수료 스케줄 (D4: 선지급 12회 = 12행, D5: nullable 계층 컬럼, D6: 환수 CLAWBACK_WINDOW_MONTHS)
-- ─────────────────────────────────────────────
CREATE TABLE commission_schedules (
    id                   BIGSERIAL      PRIMARY KEY,
    commission_id        UUID           NOT NULL,
    policy_id            UUID           NOT NULL,   -- insurance_policies.policy_id 비검증 참조
    fc_id                VARCHAR(64)    NOT NULL,

    -- D5: 계층 수수료 스키마 여지 O, 계산 로직 X — 이번 범위에선 항상 'FC'
    recipient_type       VARCHAR(20),              -- nullable (D5); 이번 범위: 항상 'FC'
    parent_commission_id UUID,                     -- 계층 수수료 상위 행. nullable (D5 — 계산 미구현)

    -- D4: 선지급 회차 1~12
    installment_no       INTEGER        NOT NULL,  -- 회차 번호 1~12
    installment_amount   NUMERIC(19,2)  NOT NULL,  -- 회차 지급액 (D6: 나머지는 마지막 회차에 가산)
    first_year_total     NUMERIC(19,2)  NOT NULL,  -- 초년도 총액 기준 (12회 합산 = first_year_total)

    -- 지급 정보
    due_date             DATE           NOT NULL,
    paid_at              TIMESTAMPTZ,
    paid_amount          NUMERIC(19,2),
    status               VARCHAR(30)    NOT NULL DEFAULT 'SCHEDULED',
    -- SCHEDULED: 지급 예정, PAID: 지급완료, CLAWBACK_PENDING: 환수대기, CLAWED_BACK: 환수완료, CANCELLED: 취소

    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version              BIGINT         NOT NULL DEFAULT 0,

    -- D4: 유니크 제약은 UNIQUE(policy_id, recipient_type, fc_id, installment_no) 하나로 통일.
    --     UNIQUE(policy_id, recipient_type, fc_id) 와 UNIQUE(policy_id, installment_no) 를 함께 두는 안은 폐기.
    CONSTRAINT uq_commission_schedule
        UNIQUE (policy_id, recipient_type, fc_id, installment_no),
    CONSTRAINT chk_commission_installment_range
        CHECK (installment_no >= 1 AND installment_no <= 12),
    CONSTRAINT chk_commission_installment_amount_positive
        CHECK (installment_amount > 0),
    CONSTRAINT chk_commission_first_year_total_positive
        CHECK (first_year_total > 0),
    CONSTRAINT chk_commission_status
        CHECK (status IN ('SCHEDULED', 'PAID', 'CLAWBACK_PENDING', 'CLAWED_BACK', 'CANCELLED')),
    CONSTRAINT chk_commission_recipient_type
        CHECK (recipient_type IS NULL OR recipient_type IN ('FC', 'MANAGER', 'DIRECTOR'))
);

CREATE UNIQUE INDEX uq_commission_id ON commission_schedules (commission_id);
CREATE INDEX idx_commission_policy ON commission_schedules (policy_id);
CREATE INDEX idx_commission_fc ON commission_schedules (fc_id);
-- 월 마감 배치가 SCHEDULED 만 훑는다
CREATE INDEX idx_commission_due_scheduled ON commission_schedules (due_date)
    WHERE status = 'SCHEDULED';
-- 환수 트리거 조회
CREATE INDEX idx_commission_clawback_pending ON commission_schedules (policy_id)
    WHERE status IN ('PAID', 'CLAWBACK_PENDING');

COMMENT ON TABLE insurance_products IS 'GA 보험대리점 상품 카탈로그. DB-per-service: 외부 서비스 참조 금지 (D3). product_code 자연키.';
COMMENT ON TABLE consultations IS '설계사 상담 칸반. 상태머신 D2: NEW→ASSIGNED→IN_PROGRESS→COMPLETED|LOST (5개 상태, 5개 전이만 허용).';
COMMENT ON TABLE insured_person_pii IS '피보험자 주민등록번호. 별도 테이블 분리 + AES-GCM 암호화(InsurancePiiEncryptionConverter, INSURANCE_ENC_KEY).';
COMMENT ON TABLE contractor_pii IS '계약자 연락처. 별도 테이블 분리 + AES-GCM 암호화(InsurancePiiEncryptionConverter, INSURANCE_ENC_KEY).';
COMMENT ON TABLE insurance_applications IS '청약 언더라이팅. 상태머신: SUBMITTED→UNDER_REVIEW→APPROVED|REJECTED.';
COMMENT ON TABLE insurance_policies IS '계약 SoR. D7 상태머신 7개 전이만 허용(그 외 InvalidPolicyTransitionException). policy_number 자연키(3단 멱등성 L3).';
COMMENT ON TABLE policy_coverages IS '계약 보장 내역. coverage_amount 는 loan/investment 가 신용보강 관점으로 이벤트 소비.';
COMMENT ON TABLE servicing_changes IS '유지·변경 append-only 이력. UPDATE 절대 금지 — trg_servicing_changes_append_only 가 강제.';
COMMENT ON TABLE commission_schedules IS '수수료 선지급 스케줄. D4: 12회 = 12행, UNIQUE(policy_id,recipient_type,fc_id,installment_no). D5: recipient_type·parent_commission_id nullable (계층 수수료 여지, 계산 로직 미구현). D6: 환수 창구는 도메인 상수 CLAWBACK_WINDOW_MONTHS 하나로 통일.';
