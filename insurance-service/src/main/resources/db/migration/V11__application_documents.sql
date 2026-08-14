-- V11: 청약서류 첨부 + OCR 대사 (ADR 0036 확산 — card V10 expense_receipts 와 동형)
--
-- application_documents: 업로드된 청약서 스캔 1건 = 1행. 파일 본문(bytea)과 OCR 추출 결과·대사
--   상태를 함께 보관 (company 문서함 선례 — 외부 스토리지 없이 DB 보관).
--   (application_id, file_hash) : 멱등 키 — 같은 파일 재업로드는 새 행도 OCR 재호출도 만들지 않는다.
--   PII 최소화: 주민등록번호·연락처는 추출·저장하지 않는다 (프롬프트에서 배제).

CREATE TABLE application_documents (
    id                BIGSERIAL       PRIMARY KEY,
    application_id    UUID            NOT NULL,                -- insurance_applications.application_id 비검증 참조 (V1 PII 관례)
    uploaded_by       VARCHAR(64)     NOT NULL,                -- JWT 주체 파생 업로더 (FcIdentity)
    file_name         VARCHAR(255)    NOT NULL,
    content_type      VARCHAR(100)    NOT NULL,
    file_hash         VARCHAR(64)     NOT NULL,                -- SHA-256 hex
    size_bytes        BIGINT          NOT NULL,
    content           BYTEA           NOT NULL,                -- 파일 본문 (불변)
    contractor_name   VARCHAR(100),                            -- OCR 계약자 성명 (참고 정보, 판정 불사용)
    insured_name      VARCHAR(100),                            -- OCR 피보험자 성명 (참고 정보)
    product_name      VARCHAR(200),                            -- OCR 상품명 (참고 정보)
    application_date  DATE,                                    -- OCR 청약일 (판독 실패 NULL → NEEDS_REVIEW)
    annual_premium    NUMERIC(19,2)   NOT NULL,                -- OCR 연 보험료 (필수 — 못 읽으면 503)
    coverage_amount   NUMERIC(19,2),                           -- OCR 보장금액 (판독 실패 NULL → NEEDS_REVIEW)
    confidence        NUMERIC(3,2)    NOT NULL,                -- 판독 신뢰도 0~1
    ocr_model         VARCHAR(100)    NOT NULL,                -- 감사·재현용 모델 식별자
    status            VARCHAR(20)     NOT NULL,
    match_note        VARCHAR(500),                            -- 불일치·리뷰 판정 근거
    reviewed_by       VARCHAR(64),                             -- 리뷰어 식별자
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_application_document_status
        CHECK (status IN ('EXTRACTED', 'MATCHED', 'MISMATCHED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_document_premium_positive    CHECK (annual_premium > 0),
    CONSTRAINT chk_document_coverage_positive   CHECK (coverage_amount IS NULL OR coverage_amount > 0),
    CONSTRAINT chk_document_confidence_range    CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_document_size_positive       CHECK (size_bytes > 0)
);

-- 멱등 키 — 같은 파일 재업로드 차단의 최후 방어선
CREATE UNIQUE INDEX uq_application_document_file ON application_documents (application_id, file_hash);
-- 승인 게이트의 최신 서류 조회 (application_id + 최신순)
CREATE INDEX idx_application_document_latest ON application_documents (application_id, created_at DESC, id DESC);
-- 리뷰 큐 조회 (NEEDS_REVIEW 목록)
CREATE INDEX idx_application_document_status ON application_documents (status);

COMMENT ON TABLE application_documents IS '청약서류. (application_id, file_hash)가 멱등 키 — 같은 파일 재업로드는 OCR 재호출 없이 기존 행 반환 (ADR 0036).';
COMMENT ON COLUMN application_documents.status IS 'EXTRACTED→(자동 대사)→MATCHED|MISMATCHED|NEEDS_REVIEW, NEEDS_REVIEW→(리뷰)→MATCHED|MISMATCHED. 종결 번복은 새 서류 첨부로만.';
COMMENT ON COLUMN application_documents.annual_premium IS 'OCR 추출 연 보험료 — 청약 desired_premium 과 compareTo 정확 일치해야 MATCHED (수수료 12회 스케줄의 원천).';
