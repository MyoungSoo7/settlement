-- V5: 셀러↔기업 매핑 (ADR 0023 Phase 3 후속)
--
-- company_sellers      : user.registered 로 수신한 셀러(회원) 등록 — 링크 대상 목록. sellerId PK 멱등.
-- company_seller_links : 셀러↔기업(종목코드) 명시 링크. user.registered 에 기업 연결 키가 없어
--                        (userId/email 만) 자동 매핑이 불가능 → admin 명시 링크로 맺는다.
--                        평판 등급 변동 시 링크된 sellerId 를 이벤트에 동봉 → loan 이 셀러 신용에 반영.

CREATE TABLE IF NOT EXISTS company_sellers (
    seller_id  BIGINT      PRIMARY KEY,
    email      VARCHAR(320),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS company_seller_links (
    seller_id  BIGINT      PRIMARY KEY,                    -- 한 셀러는 한 기업에 링크
    stock_code VARCHAR(6)  NOT NULL REFERENCES companies (stock_code),
    linked_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_company_seller_links_stock ON company_seller_links (stock_code);
