-- V10: 지출보고서 영수증 첨부 + OCR 대사 (ADR 0036)
--
-- expense_receipts: 업로드된 영수증 1건 = 1행. 파일 본문(bytea)과 OCR 추출 결과·대사 상태를 함께 보관
--   (company 문서함 선례 — 외부 스토리지 없이 DB 보관).
--   (report_id, file_hash) : 멱등 키 — 같은 파일 재업로드는 새 행도 OCR 재호출도 만들지 않는다.

CREATE TABLE expense_receipts (
    id                BIGSERIAL       PRIMARY KEY,
    report_id         VARCHAR(64)     NOT NULL,                -- expense_reports.report_id 참조 (자연키)
    capture_id        VARCHAR(64)     NOT NULL,                -- 3자 대사의 매입 축
    organization_id   BIGINT          NOT NULL,
    uploader_user_id  BIGINT          NOT NULL,                -- JWT 주체 파생 업로더 (보고서 holder 와 대조됨)
    file_name         VARCHAR(255)    NOT NULL,
    content_type      VARCHAR(100)    NOT NULL,
    file_hash         VARCHAR(64)     NOT NULL,                -- SHA-256 hex
    size_bytes        BIGINT          NOT NULL,
    content           BYTEA           NOT NULL,                -- 파일 본문 (불변)
    merchant_name     VARCHAR(200),                            -- OCR 상호명 (판독 실패 NULL, 판정 불사용)
    transaction_date  DATE,                                    -- OCR 거래일 (판독 실패 NULL → NEEDS_REVIEW)
    total_amount      NUMERIC(19,2)   NOT NULL,                -- OCR 총액 (필수 — 못 읽으면 503)
    confidence        NUMERIC(3,2)    NOT NULL,                -- 판독 신뢰도 0~1
    ocr_model         VARCHAR(100)    NOT NULL,                -- 감사·재현용 모델 식별자
    status            VARCHAR(20)     NOT NULL,
    match_note        VARCHAR(500),                            -- 불일치·리뷰 판정 근거
    reviewed_by       BIGINT,                                  -- 관리자 리뷰어 userId
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_expense_receipt_status
        CHECK (status IN ('EXTRACTED', 'MATCHED', 'MISMATCHED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_receipt_amount_positive   CHECK (total_amount > 0),
    CONSTRAINT chk_receipt_confidence_range  CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_receipt_size_positive     CHECK (size_bytes > 0)
);

-- 멱등 키 — 같은 파일 재업로드 차단의 최후 방어선
CREATE UNIQUE INDEX uq_receipt_report_file ON expense_receipts (report_id, file_hash);
-- 승인 게이트의 최신 영수증 조회 (report_id + 최신순)
CREATE INDEX idx_receipt_report_created ON expense_receipts (report_id, created_at DESC, id DESC);
-- 리뷰 큐 조회 (조직별 NEEDS_REVIEW 목록)
CREATE INDEX idx_receipt_org_status ON expense_receipts (organization_id, status);

COMMENT ON TABLE expense_receipts IS '지출보고서 영수증. (report_id, file_hash)가 멱등 키 — 같은 파일 재업로드는 OCR 재호출 없이 기존 행 반환 (ADR 0036).';
COMMENT ON COLUMN expense_receipts.status IS 'EXTRACTED→(자동 대사)→MATCHED|MISMATCHED|NEEDS_REVIEW, NEEDS_REVIEW→(관리자 리뷰)→MATCHED|MISMATCHED. 종결 번복은 새 영수증 첨부로만.';
COMMENT ON COLUMN expense_receipts.total_amount IS 'OCR 추출 총액 — 매입(card_captures.captured_amount)과 compareTo 정확 일치해야 MATCHED.';
