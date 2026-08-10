-- V7: 대면 상품설명서 교부 이력 (disclosure_deliveries)
--
-- 완전판매 증빙 — 누가·언제·어떤 상품설명서(문서 해시)를 어느 채널로 교부했는지 남긴다.
-- 문서 자체는 상품 카탈로그에서 온디맨드 렌더링(PDF)하고, 교부 시점의 문서 SHA-256 과
-- 상품 조건 스냅샷을 이력에 고정한다 — 이후 상품 개정이 있어도 "그때 무엇을 설명했는지"가 재현된다.
-- 규제 증빙이므로 append-only (servicing_changes 와 동일 트리거 강제).
CREATE TABLE disclosure_deliveries (
    id                 BIGSERIAL     PRIMARY KEY,
    delivery_id        UUID          NOT NULL,
    application_id     UUID,                       -- 청약 경유 교부 시 (사전 상담 교부는 NULL)
    product_code       VARCHAR(32)   NOT NULL,     -- insurance_products.product_code 비검증 참조
    sales_channel      VARCHAR(20)   NOT NULL,     -- FC(설계사 대면) | BANCA(은행 창구)
    delivered_by       VARCHAR(64)   NOT NULL,     -- 교부자 — FC id 또는 은행 창구 직원 id
    partner_bank_code  VARCHAR(32),                -- BANCA 교부 시 판매 은행
    contractor_name    VARCHAR(100)  NOT NULL,     -- 교부 상대(계약자) — PII 최소화: 이름만
    document_sha256    VARCHAR(64)   NOT NULL,     -- 교부된 PDF 바이트의 SHA-256 hex (무결성 증빙)
    product_snapshot   JSONB         NOT NULL,     -- 교부 시점 상품 조건 (설명 내용 재현용)
    delivered_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_disclosure_channel CHECK (sales_channel IN ('FC', 'BANCA')),
    -- BANCA 교부는 판매 은행 필수, FC 교부는 은행 없음 (V6 chk_policy_banca_bank 와 동형)
    CONSTRAINT chk_disclosure_banca_bank
        CHECK ((sales_channel = 'BANCA') = (partner_bank_code IS NOT NULL))
);

CREATE UNIQUE INDEX uq_disclosure_delivery_id ON disclosure_deliveries (delivery_id);
CREATE INDEX idx_disclosure_application ON disclosure_deliveries (application_id);
CREATE INDEX idx_disclosure_product ON disclosure_deliveries (product_code, delivered_at DESC);

-- append-only 강제 (UPDATE·DELETE 거부) — 완전판매 증빙 변조 차단
CREATE OR REPLACE FUNCTION disclosure_deliveries_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'disclosure_deliveries 는 append-only 입니다: % 연산 불가 (완전판매 증빙 변조 차단)', TG_OP;
END;
$$;
CREATE TRIGGER trg_disclosure_deliveries_append_only
    BEFORE UPDATE OR DELETE ON disclosure_deliveries
    FOR EACH ROW EXECUTE FUNCTION disclosure_deliveries_block_modify();

COMMENT ON TABLE disclosure_deliveries IS
    '대면 상품설명서 교부 이력 — 완전판매 증빙(교부자·시점·문서 SHA-256·상품 스냅샷). append-only.';
