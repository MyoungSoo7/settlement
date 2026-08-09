-- V9: 가입설계(Proposal) — 요율 테이블 + 설계 스냅샷
--
-- 가입설계 = 요율 기반 보험료 산출 + 설계서 발행 + 청약 전환.
-- 설계 결정:
--   D-P1: 요율은 코드(enum)가 아니라 DB row + effective_from 버저닝 — 요율 개정에 배포 불요,
--         과거 설계 재현 가능 (economics 지표 카탈로그와 동일 패턴).
--   D-P2: 산출 결과는 INSERT-only 스냅샷 — 재산출은 새 proposal 행. 산출에 쓴 요율 행(rate_table_id)과
--         적용 요율을 함께 보존한다 (settlement commission_rate 영구보존과 동일 철학).
--   D-P3: 설계 경유 청약은 보험료를 클라이언트에서 받지 않는다 — 전환 시 서버가 annual_premium 을 주입.
--         1설계 1청약은 uq_proposal_converted_application 이 DB 로 보장.
--   PII 최소화: 생년월일은 저장하지 않는다 — 산출 시점 보험나이(insurance_age)만 스냅샷.

-- ─────────────────────────────────────────────
-- 1. 요율 테이블 (D-P1: effective_from 버저닝, 개정 = 새 행)
-- ─────────────────────────────────────────────
CREATE TABLE premium_rate_tables (
    id                 BIGSERIAL     PRIMARY KEY,
    product_code       VARCHAR(32)   NOT NULL,   -- insurance_products.product_code 비검증 참조
    gender             VARCHAR(1)    NOT NULL,   -- M | F (Hibernate @Enumerated(STRING) validate 와 타입 일치)
    age_from           INTEGER       NOT NULL,   -- 보험나이 구간 하한 (포함)
    age_to             INTEGER       NOT NULL,   -- 보험나이 구간 상한 (포함)
    payment_term_years INTEGER       NOT NULL,   -- 납입기간(년)
    rate_per_mille     NUMERIC(9,4)  NOT NULL,   -- 보장금액 1,000원당 연 보험료
    effective_from     DATE          NOT NULL,   -- 요율 적용 개시일 — 최신 행이 이긴다
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_rate_gender CHECK (gender IN ('M', 'F')),
    CONSTRAINT chk_rate_age_range CHECK (age_from >= 0 AND age_from <= age_to),
    CONSTRAINT chk_rate_term_positive CHECK (payment_term_years > 0),
    CONSTRAINT chk_rate_per_mille_positive CHECK (rate_per_mille > 0),
    -- 같은 (상품, 성별, 구간 시작, 납입기간, 개시일) 중복 요율 차단
    CONSTRAINT uq_rate_band UNIQUE (product_code, gender, age_from, payment_term_years, effective_from)
);

-- 산출 조회: 상품·성별·납입기간·개시일 최신 순으로 훑고 나이 구간을 앱이 대조
CREATE INDEX idx_rate_lookup
    ON premium_rate_tables (product_code, gender, payment_term_years, effective_from DESC);

-- ─────────────────────────────────────────────
-- 2. 가입설계 스냅샷 (D-P2: INSERT-only, 상태머신 QUOTED→CONVERTED|EXPIRED)
-- ─────────────────────────────────────────────
CREATE TABLE proposal_quotes (
    id                       BIGSERIAL     PRIMARY KEY,
    proposal_id              UUID          NOT NULL,
    consultation_id          UUID,                     -- 상담 경유 유입 시만 (nullable)
    product_code             VARCHAR(32)   NOT NULL,   -- insurance_products.product_code 비검증 참조
    fc_id                    VARCHAR(64)   NOT NULL,   -- 설계자 — 소유권 대조 기준(IDOR)
    insured_name             VARCHAR(100)  NOT NULL,
    insured_gender           VARCHAR(1)    NOT NULL,   -- M | F (Hibernate validate 와 타입 일치)
    insurance_age            INTEGER       NOT NULL,   -- 산출 시점 보험나이 (생년월일 미저장 — PII 최소화)
    coverage_amount          NUMERIC(19,2) NOT NULL,
    payment_term_years       INTEGER       NOT NULL,

    -- 산출 스냅샷 (D-P2: 어떤 요율로 얼마가 나왔는지 영구 보존)
    rate_table_id            BIGINT        NOT NULL,   -- premium_rate_tables.id — 산출에 쓴 요율 행
    applied_rate_per_mille   NUMERIC(9,4)  NOT NULL,   -- 산출 시점 적용 요율 (요율 개정에도 불변)
    annual_premium           NUMERIC(19,2) NOT NULL,   -- 산출 결과 — 원 단위 HALF_UP

    sales_channel            VARCHAR(20)   NOT NULL,   -- FC | BANCA
    partner_bank_code        VARCHAR(32),              -- BANCA 설계 시 판매 은행

    status                   VARCHAR(20)   NOT NULL DEFAULT 'QUOTED',
    quoted_on                DATE          NOT NULL,   -- 산출일 (KST)
    valid_until              DATE          NOT NULL,   -- 산출일 + 30일 — 경과 시 전환 불가
    converted_application_id UUID,                     -- CONVERTED 시 생성된 청약 (1설계 1청약)

    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version                  BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_proposal_status CHECK (status IN ('QUOTED', 'CONVERTED', 'EXPIRED')),
    CONSTRAINT chk_proposal_gender CHECK (insured_gender IN ('M', 'F')),
    CONSTRAINT chk_proposal_age_non_negative CHECK (insurance_age >= 0),
    CONSTRAINT chk_proposal_coverage_positive CHECK (coverage_amount > 0),
    CONSTRAINT chk_proposal_premium_positive CHECK (annual_premium > 0),
    CONSTRAINT chk_proposal_rate_positive CHECK (applied_rate_per_mille > 0),
    CONSTRAINT chk_proposal_term_positive CHECK (payment_term_years > 0),
    CONSTRAINT chk_proposal_validity CHECK (valid_until >= quoted_on),
    -- BANCA 설계는 판매 은행 필수, FC 설계는 은행 없음 (V6 chk_policy_banca_bank 와 동형)
    CONSTRAINT chk_proposal_banca_bank
        CHECK ((sales_channel = 'BANCA') = (partner_bank_code IS NOT NULL)),
    -- CONVERTED ↔ 전환된 청약 존재 동치 — 반쪽 전환 상태 차단
    CONSTRAINT chk_proposal_converted_application
        CHECK ((status = 'CONVERTED') = (converted_application_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_proposal_id ON proposal_quotes (proposal_id);
-- D-P3: 한 설계는 한 청약으로만 전환된다 — DB 레벨 보장
CREATE UNIQUE INDEX uq_proposal_converted_application
    ON proposal_quotes (converted_application_id) WHERE converted_application_id IS NOT NULL;
CREATE INDEX idx_proposal_fc ON proposal_quotes (fc_id);
-- 만기 스윕 배치가 QUOTED 만 훑는다
CREATE INDEX idx_proposal_expiry_scan ON proposal_quotes (valid_until) WHERE status = 'QUOTED';

COMMENT ON TABLE premium_rate_tables IS '가입설계 요율. D-P1: effective_from 버저닝 — 개정은 새 행, 과거 설계 재현 가능. rate_per_mille = 보장 1,000원당 연 보험료.';
COMMENT ON TABLE proposal_quotes IS '가입설계 스냅샷. D-P2: INSERT-only(재산출=새 행), 산출에 쓴 요율 행·적용 요율 영구 보존. 상태머신 QUOTED→CONVERTED|EXPIRED. D-P3: 전환 시 보험료는 서버가 주입, 1설계 1청약(uq_proposal_converted_application).';
