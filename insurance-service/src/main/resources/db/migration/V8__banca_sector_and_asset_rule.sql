-- V8: 방카 25%룰 정밀화 — 생보/손보 부문 분리 계산 + 자산 2조 적용 요건
--
-- 실제 규제는 판매비중을 생명보험/손해보험 부문별로 각각 계산하고,
-- 자산총액 2조원 이상인 금융기관보험대리점(은행)에만 적용된다. V6 의 단일 풀
-- 집계를 부문 풀로 쪼개고, 은행 자산 레지스트리를 도입한다.
--   - insurance_products.insurer_sector : 'LIFE' | 'NON_LIFE' — 집계 풀 분리 기준.
--     NULL 은 미분류 → 집계 제외(카탈로그 정비 대상, insurer_code NULL 과 동일 원칙).
--   - banca_partner_banks : 은행 자산총액 레지스트리 — 적용 대상(자산 2조 이상) 판정 입력.
--     미등록 은행은 적용 대상으로 본다(fail-closed) — 위반 누락이 면제 오탐보다 나쁘다.

-- 1) 상품 카탈로그 — 원수사 부문
ALTER TABLE insurance_products
    ADD COLUMN insurer_sector VARCHAR(10);

ALTER TABLE insurance_products
    ADD CONSTRAINT chk_product_insurer_sector
        CHECK (insurer_sector IS NULL OR insurer_sector IN ('LIFE', 'NON_LIFE'));

COMMENT ON COLUMN insurance_products.insurer_sector IS
    '원수사 부문 — LIFE(생보) | NON_LIFE(손보). 방카 25%룰 비중의 분모 풀 분리 기준. NULL 은 집계 제외(경고 대상).';

-- 2) 방카 파트너 은행 자산 레지스트리
CREATE TABLE banca_partner_banks (
    bank_code    VARCHAR(32)    PRIMARY KEY,
    bank_name    VARCHAR(100)   NOT NULL,
    total_assets NUMERIC(20, 2) NOT NULL CHECK (total_assets >= 0),
    as_of_date   DATE           NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

COMMENT ON TABLE banca_partner_banks IS
    '방카 파트너 은행 자산총액 레지스트리 — 25%룰 적용 대상(자산 2조 이상, 경계 포함) 판정 입력. '
    '미등록 은행은 적용 대상으로 본다(fail-closed).';
COMMENT ON COLUMN banca_partner_banks.total_assets IS
    '자산총액(원). 2조(2,000,000,000,000) 이상이면 25%룰 적용 대상 — 임계값은 도메인 상수가 정본.';
COMMENT ON COLUMN banca_partner_banks.as_of_date IS
    '자산총액 기준일 — 직전 사업연도말.';
