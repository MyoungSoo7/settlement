-- V6: 기업 문서함 (company-service)
--
-- 외부 파이프라인(예: trusted-ceo-agent 브리핑)이 만든 산출물(docx·pdf·png·md)을 기업 단위로
-- 보관해 CEO 콘솔(/admin/ceo/companies)에서 평판 옆에 노출한다. 파일 바이트는 BYTEA 로 DB 에
-- 직접 저장 — 브리핑은 수백 KB 수준이고 DB-per-service 원칙상 별도 오브젝트 스토리지 의존을
-- 만들지 않는다(20MB 상한은 도메인이 강제).
-- (stock_code, file_name) UNIQUE — 같은 이름 재업로드는 교체(최신 브리핑으로 갱신) 시맨틱.

CREATE TABLE IF NOT EXISTS company_documents (
    id           BIGSERIAL    PRIMARY KEY,
    stock_code   VARCHAR(6)   NOT NULL REFERENCES companies (stock_code),
    title        VARCHAR(200) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    content      BYTEA        NOT NULL,
    uploaded_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_company_documents_stock_file UNIQUE (stock_code, file_name),
    CONSTRAINT chk_company_documents_size CHECK (size_bytes > 0)
);

CREATE INDEX IF NOT EXISTS idx_company_documents_stock_uploaded
    ON company_documents (stock_code, uploaded_at DESC);
