-- 예치금 증빙 첨부 + OCR 지연 대사 (ADR 0036 확산 — card V10 expense_receipts 와 동형)
--
-- deposit_proofs: 수기 기표(credit/debit)의 증빙(이체확인증) 1건 = 1행. 파일 본문(bytea)과 OCR 추출
--   결과·대사 상태를 함께 보관.
--   앵커 = 수기 기표의 호출자 지정 멱등 키 (seller_id, reference_type, reference_id) — 기표 전에
--   확정되는 식별자라, 선행 애그리거트 없는 즉시 반영 구조에서도 "첨부 → 기표" 순서를 강제할 수 있다.
--   값 대사는 기표 시점에 요청 값과 대조(지연 대사 — DepositProofGate).
--   (앵커, file_hash) : 멱등 키 — 같은 파일 재업로드는 새 행도 OCR 재호출도 만들지 않는다.

CREATE TABLE deposit_proofs (
    id                BIGSERIAL       PRIMARY KEY,
    seller_id         BIGINT          NOT NULL,
    reference_type    VARCHAR(40)     NOT NULL,              -- 수기 기표의 referenceType (예: MANUAL_TOPUP)
    reference_id      VARCHAR(100)    NOT NULL,              -- 호출자 지정 멱등 키 — 기표 전 확정
    uploaded_by       BIGINT          NOT NULL,              -- JWT 주체 파생 업로더 userId (ADMIN)
    file_name         VARCHAR(255)    NOT NULL,
    content_type      VARCHAR(100)    NOT NULL,
    file_hash         VARCHAR(64)     NOT NULL,              -- SHA-256 hex
    size_bytes        BIGINT          NOT NULL,
    content           BYTEA           NOT NULL,              -- 파일 본문 (불변)
    sender_name       VARCHAR(100),                          -- OCR 입금자명 (참고 정보, 판정 불사용)
    transfer_date     DATE,                                  -- OCR 이체일 (판독 실패 NULL → 첨부 시 NEEDS_REVIEW)
    transfer_amount   NUMERIC(19,2)   NOT NULL,              -- OCR 이체금액 (필수 — 못 읽으면 503)
    confidence        NUMERIC(3,2)    NOT NULL,              -- 판독 신뢰도 0~1
    ocr_model         VARCHAR(100)    NOT NULL,              -- 감사·재현용 모델 식별자
    status            VARCHAR(20)     NOT NULL,
    match_note        VARCHAR(500),                          -- 불일치·리뷰 판정 근거
    reviewed_by       BIGINT,                                -- 운영자 리뷰어 userId
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_deposit_proof_status
        CHECK (status IN ('EXTRACTED', 'MATCHED', 'MISMATCHED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_proof_amount_positive     CHECK (transfer_amount > 0),
    CONSTRAINT chk_proof_confidence_range    CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_proof_size_positive       CHECK (size_bytes > 0)
);

-- 멱등 키 — 같은 파일 재업로드 차단의 최후 방어선
CREATE UNIQUE INDEX uq_deposit_proof_file
    ON deposit_proofs (seller_id, reference_type, reference_id, file_hash);
-- 기표 게이트의 최신 증빙 조회 (앵커 + 최신순)
CREATE INDEX idx_deposit_proof_latest
    ON deposit_proofs (seller_id, reference_type, reference_id, created_at DESC, id DESC);
-- 리뷰 큐 조회 (NEEDS_REVIEW 목록)
CREATE INDEX idx_deposit_proof_status ON deposit_proofs (status);

COMMENT ON TABLE deposit_proofs IS '예치금 수기 기표 증빙. (seller_id, reference_type, reference_id, file_hash)가 멱등 키 — 같은 파일 재업로드는 OCR 재호출 없이 기존 행 반환 (ADR 0036).';
COMMENT ON COLUMN deposit_proofs.status IS 'EXTRACTED(기표 대기)→(지연 대사/첨부 시 리뷰행)→MATCHED|MISMATCHED|NEEDS_REVIEW, NEEDS_REVIEW→(리뷰)→MATCHED|MISMATCHED. 종결 번복은 새 증빙 첨부로만.';
COMMENT ON COLUMN deposit_proofs.transfer_amount IS 'OCR 추출 이체금액 — 기표 요청 금액과 compareTo 정확 일치해야 MATCHED (잔고 단일 진실원).';
