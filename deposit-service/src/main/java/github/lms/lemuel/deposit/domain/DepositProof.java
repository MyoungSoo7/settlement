package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예치금 증빙 애그리거트 (순수 POJO — 프레임워크 의존 0, ADR 0036 확산).
 *
 * <p>수기 기표의 증빙(이체확인증 등) 1건의 OCR 추출 결과와 대사 상태를 소유한다. 앵커는 수기 기표의
 * 호출자 지정 멱등 키 {@code (sellerId, referenceType, referenceId)} — 기표 전에 이미 확정된
 * 식별자라 선행 애그리거트 없이도 첨부→기표 순서를 강제할 수 있다. 멱등 키는
 * {@code (앵커, fileHash)}(영속 UNIQUE 와 결합) — 같은 파일 재업로드는 새 행도 OCR 재호출도 만들지
 * 않는다.
 *
 * <p>상태 전이는 {@link DepositProofStatus} 전이표로만 하며, 종결(MATCHED/MISMATCHED) 이후의 번복은
 * 새 증빙 첨부로만 한다. public setter 없음 — 재구성은 빌더(영속 전용).
 */
public class DepositProof {

    private Long id;
    private final Long sellerId;
    private final String referenceType;      // 수기 기표의 referenceType (예: MANUAL_TOPUP)
    private final String referenceId;        // 호출자 지정 멱등 키 — 기표 전 확정
    private final Long uploadedBy;           // JWT 주체에서 파생된 업로더 userId (ADMIN)
    private final String fileName;
    private final String contentType;
    private final String fileHash;           // SHA-256
    private final Long sizeBytes;
    private final ExtractedTransferProof extracted;
    private final String ocrModel;           // 감사·재현용 모델 식별자
    private DepositProofStatus status;
    private String matchNote;
    private Long reviewedBy;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private DepositProof(Builder b) {
        this.id = b.id;
        this.sellerId = b.sellerId;
        this.referenceType = b.referenceType;
        this.referenceId = b.referenceId;
        this.uploadedBy = b.uploadedBy;
        this.fileName = b.fileName;
        this.contentType = b.contentType;
        this.fileHash = b.fileHash;
        this.sizeBytes = b.sizeBytes;
        this.extracted = b.extracted;
        this.ocrModel = b.ocrModel;
        this.status = b.status;
        this.matchNote = b.matchNote;
        this.reviewedBy = b.reviewedBy;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
    }

    /** OCR 추출 직후의 증빙을 만든다 — 기표 대기(EXTRACTED). */
    public static DepositProof extracted(Long sellerId, String referenceType, String referenceId,
                                         Long uploadedBy, String fileName, String contentType,
                                         String fileHash, Long sizeBytes, ExtractedTransferProof extracted,
                                         String ocrModel, LocalDateTime now) {
        requirePositive(sellerId, "sellerId");
        requireText(referenceType, "referenceType");
        requireText(referenceId, "referenceId");
        requirePositive(uploadedBy, "uploadedBy");
        requireText(fileName, "파일명");
        requireText(contentType, "콘텐츠 타입");
        requireText(fileHash, "파일 해시");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new InvalidDepositProofException("파일 크기는 양수여야 합니다: " + sizeBytes);
        }
        if (extracted == null) {
            throw new InvalidDepositProofException("OCR 추출 결과는 필수입니다");
        }
        requireText(ocrModel, "OCR 모델명");
        Objects.requireNonNull(now, "now");
        Builder b = new Builder();
        b.sellerId = sellerId;
        b.referenceType = referenceType.trim();
        b.referenceId = referenceId.trim();
        b.uploadedBy = uploadedBy;
        b.fileName = fileName.trim();
        b.contentType = contentType.trim();
        b.fileHash = fileHash.trim();
        b.sizeBytes = sizeBytes;
        b.extracted = extracted;
        b.ocrModel = ocrModel.trim();
        b.status = DepositProofStatus.EXTRACTED;
        b.createdAt = now;
        b.updatedAt = now;
        return new DepositProof(b);
    }

    /** 대사 판정 적용 — EXTRACTED 에서만 가능(첨부 시 신뢰도 미달 / 기표 시 지연 대사). */
    public void applyDecision(DepositProofMatchDecision decision, LocalDateTime now) {
        Objects.requireNonNull(decision, "decision");
        transitionTo(decision.status(), now);
        this.matchNote = decision.note();
    }

    /** 운영자 리뷰 확정 — NEEDS_REVIEW → MATCHED. */
    public void reviewMatch(Long reviewerId, String note, LocalDateTime now) {
        review(DepositProofStatus.MATCHED, reviewerId, note, now);
    }

    /** 운영자 리뷰 반려 — NEEDS_REVIEW → MISMATCHED. */
    public void reviewMismatch(Long reviewerId, String note, LocalDateTime now) {
        review(DepositProofStatus.MISMATCHED, reviewerId, note, now);
    }

    private void review(DepositProofStatus next, Long reviewerId, String note, LocalDateTime now) {
        if (status != DepositProofStatus.NEEDS_REVIEW) {
            throw new InvalidDepositProofException(
                    "운영자 리뷰는 NEEDS_REVIEW 에서만 가능합니다: 현재 상태=" + status);
        }
        requirePositive(reviewerId, "reviewerId");
        transitionTo(next, now);
        this.reviewedBy = reviewerId;
        this.matchNote = note;
    }

    private void transitionTo(DepositProofStatus next, LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        if (!status.canTransitionTo(next)) {
            throw new InvalidDepositProofException("예치금 증빙 상태 전이 불가: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new InvalidDepositProofException(label + "은(는) 필수입니다");
        }
    }

    private static void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new InvalidDepositProofException(label + "은(는) 양수여야 합니다: " + value);
        }
    }

    // ── getters ──

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public Long getUploadedBy() { return uploadedBy; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getFileHash() { return fileHash; }
    public Long getSizeBytes() { return sizeBytes; }
    public ExtractedTransferProof getExtracted() { return extracted; }
    public String getOcrModel() { return ocrModel; }
    public DepositProofStatus getStatus() { return status; }
    public String getMatchNote() { return matchNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 */
    public static class Builder {
        private Long id;
        private Long sellerId;
        private String referenceType;
        private String referenceId;
        private Long uploadedBy;
        private String fileName;
        private String contentType;
        private String fileHash;
        private Long sizeBytes;
        private ExtractedTransferProof extracted;
        private String ocrModel;
        private DepositProofStatus status;
        private String matchNote;
        private Long reviewedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder sellerId(Long v) { this.sellerId = v; return this; }
        public Builder referenceType(String v) { this.referenceType = v; return this; }
        public Builder referenceId(String v) { this.referenceId = v; return this; }
        public Builder uploadedBy(Long v) { this.uploadedBy = v; return this; }
        public Builder fileName(String v) { this.fileName = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder fileHash(String v) { this.fileHash = v; return this; }
        public Builder sizeBytes(Long v) { this.sizeBytes = v; return this; }
        public Builder extracted(ExtractedTransferProof v) { this.extracted = v; return this; }
        public Builder ocrModel(String v) { this.ocrModel = v; return this; }
        public Builder status(DepositProofStatus v) { this.status = v; return this; }
        public Builder matchNote(String v) { this.matchNote = v; return this; }
        public Builder reviewedBy(Long v) { this.reviewedBy = v; return this; }
        public Builder createdAt(LocalDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(LocalDateTime v) { this.updatedAt = v; return this; }
        public DepositProof build() { return new DepositProof(this); }
    }
}
