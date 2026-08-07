-- V6: 판매채널 차원 (방카슈랑스 — BANCA 채널) 추가
--
-- GA 설계사(FC) 대면 판매에 더해 은행 창구 판매(방카슈랑스) 채널을 도입한다.
--   - sales_channel  : 'FC'(설계사 대면) | 'BANCA'(은행 창구)
--   - partner_bank_code : BANCA 채널일 때 판매 은행 코드 (FC 채널이면 NULL)
--   - 방카 채널 계약의 수수료 수령 주체는 은행 → recipient_type 'BANK' 허용
--   - insurer_code : 상품의 원수사(보험사) 코드 — 방카 25%룰(은행별 특정 원수사 비중 제한)
--     모니터링 배치가 policies × products 조인 후 이 컬럼으로 집계한다.

-- 1) 상품 카탈로그 — 원수사 코드
ALTER TABLE insurance_products
    ADD COLUMN insurer_code VARCHAR(32);

COMMENT ON COLUMN insurance_products.insurer_code IS
    '원수사(보험사) 코드. 방카 25%룰 집계 기준 — NULL 상품은 집계에서 제외된다(경고 대상).';

-- 2) 청약 — 판매채널 + 판매 은행
ALTER TABLE insurance_applications
    ADD COLUMN sales_channel VARCHAR(20) NOT NULL DEFAULT 'FC',
    ADD COLUMN partner_bank_code VARCHAR(32);

ALTER TABLE insurance_applications
    ADD CONSTRAINT chk_application_sales_channel
        CHECK (sales_channel IN ('FC', 'BANCA')),
    -- BANCA 청약은 판매 은행이 반드시 있어야 하고, FC 청약은 은행이 있으면 안 된다
    ADD CONSTRAINT chk_application_banca_bank
        CHECK ((sales_channel = 'BANCA') = (partner_bank_code IS NOT NULL));

-- 3) 계약 — 판매채널 + 판매 은행 (청약에서 승계)
ALTER TABLE insurance_policies
    ADD COLUMN sales_channel VARCHAR(20) NOT NULL DEFAULT 'FC',
    ADD COLUMN partner_bank_code VARCHAR(32);

ALTER TABLE insurance_policies
    ADD CONSTRAINT chk_policy_sales_channel
        CHECK (sales_channel IN ('FC', 'BANCA')),
    ADD CONSTRAINT chk_policy_banca_bank
        CHECK ((sales_channel = 'BANCA') = (partner_bank_code IS NOT NULL));

-- 방카 25%룰 집계 스캔 (은행별 × 상품별) — BANCA 행만 부분 인덱스
CREATE INDEX idx_policy_banca_bank ON insurance_policies (partner_bank_code, product_code)
    WHERE sales_channel = 'BANCA';

-- 4) 수수료 — 방카 채널 수령 주체 'BANK' 허용
ALTER TABLE commission_schedules
    DROP CONSTRAINT chk_commission_recipient_type;
ALTER TABLE commission_schedules
    ADD CONSTRAINT chk_commission_recipient_type
        CHECK (recipient_type IS NULL OR recipient_type IN ('FC', 'MANAGER', 'DIRECTOR', 'BANK'));

COMMENT ON COLUMN insurance_policies.sales_channel IS
    '판매채널 — FC(설계사 대면) | BANCA(은행 창구). BANCA 면 partner_bank_code 필수 (CHECK 강제).';
COMMENT ON COLUMN insurance_policies.partner_bank_code IS
    '방카 판매 은행 코드. 수수료 수령 주체(BANK)·25%룰 집계 기준.';
