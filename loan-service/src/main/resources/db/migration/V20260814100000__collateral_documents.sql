-- 담보서류 첨부 + OCR 대사 (ADR 0036 확산 — card V10 expense_receipts 와 동형)
--
-- collateral_documents: 업로드된 담보서류(감정평가서·등기부) 1건 = 1행. 파일 본문(bytea)과 OCR 추출
--   결과·대사 상태를 함께 보관.
--   (secured_loan_id, file_hash) : 멱등 키 — 같은 파일 재업로드는 새 행도 OCR 재호출도 만들지 않는다.
--   대사 3축: 감정평가액(정확 일치) · 선순위 채권최고액(신고값 검증 — 현재 유일한 검증 수단) ·
--   평가기준일(설정 시각 ±1일). 소유자·소재지는 참고 정보(제3자 담보를 도메인이 표현하지 못한다).

CREATE TABLE collateral_documents (
    id                  BIGSERIAL       PRIMARY KEY,
    secured_loan_id     BIGINT          NOT NULL REFERENCES secured_loans(id),
    collateral_id       BIGINT          NOT NULL REFERENCES collaterals(id),
    uploaded_by         BIGINT          NOT NULL,              -- JWT 주체 파생 업로더 userId
    file_name           VARCHAR(255)    NOT NULL,
    content_type        VARCHAR(100)    NOT NULL,
    file_hash           VARCHAR(64)     NOT NULL,              -- SHA-256 hex
    size_bytes          BIGINT          NOT NULL,
    content             BYTEA           NOT NULL,              -- 파일 본문 (불변)
    owner_name          VARCHAR(100),                          -- OCR 소유자 성명 (참고 정보, 판정 불사용)
    location_text       VARCHAR(500),                          -- OCR 소재지 표시 (참고 정보)
    appraised_value     NUMERIC(19,2)   NOT NULL,              -- OCR 감정평가액 (필수 — 못 읽으면 503)
    senior_claim_amount NUMERIC(19,2),                         -- OCR 선순위 채권최고액 (판독 실패 NULL)
    appraisal_date      DATE,                                  -- OCR 평가기준일 (판독 실패 NULL → NEEDS_REVIEW)
    confidence          NUMERIC(3,2)    NOT NULL,              -- 판독 신뢰도 0~1
    ocr_model           VARCHAR(100)    NOT NULL,              -- 감사·재현용 모델 식별자
    status              VARCHAR(20)     NOT NULL,
    match_note          VARCHAR(500),                          -- 불일치·리뷰 판정 근거
    reviewed_by         BIGINT,                                -- 운영자 리뷰어 userId
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_collateral_document_status
        CHECK (status IN ('EXTRACTED', 'MATCHED', 'MISMATCHED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_coldoc_appraised_positive  CHECK (appraised_value > 0),
    CONSTRAINT chk_coldoc_senior_nonnegative  CHECK (senior_claim_amount IS NULL OR senior_claim_amount >= 0),
    CONSTRAINT chk_coldoc_confidence_range    CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_coldoc_size_positive       CHECK (size_bytes > 0)
);

-- 멱등 키 — 같은 파일 재업로드 차단의 최후 방어선
CREATE UNIQUE INDEX uq_collateral_document_file ON collateral_documents (secured_loan_id, file_hash);
-- 승인 게이트의 최신 서류 조회 (secured_loan_id + 최신순)
CREATE INDEX idx_collateral_document_latest ON collateral_documents (secured_loan_id, created_at DESC, id DESC);
-- 리뷰 큐 조회 (NEEDS_REVIEW 목록)
CREATE INDEX idx_collateral_document_status ON collateral_documents (status);

COMMENT ON TABLE collateral_documents IS '담보서류. (secured_loan_id, file_hash)가 멱등 키 — 같은 파일 재업로드는 OCR 재호출 없이 기존 행 반환 (ADR 0036).';
COMMENT ON COLUMN collateral_documents.senior_claim_amount IS 'OCR 선순위 채권최고액 — 신청자 자기신고값(collaterals.senior_claim_amount)의 현재 유일한 검증 수단.';
COMMENT ON COLUMN collateral_documents.status IS 'EXTRACTED→(자동 대사)→MATCHED|MISMATCHED|NEEDS_REVIEW, NEEDS_REVIEW→(운영자 리뷰)→MATCHED|MISMATCHED. 종결 번복은 새 서류 첨부로만.';
