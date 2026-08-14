package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 담보서류 애그리거트 (순수 POJO — 프레임워크 의존 0, ADR 0036 확산).
 *
 * <p>업로드된 담보서류(감정평가서·등기부) 1건의 OCR 추출 결과와 대사 상태를 소유한다. 멱등 키는
 * {@code (securedLoanId, fileHash)}(영속 UNIQUE 와 결합) — 같은 파일을 다시 올려도 새 행이 생기지
 * 않고 AI OCR 재호출도 이 키에서 차단된다.
 *
 * <p>상태 전이는 {@link CollateralDocumentStatus} 전이표로만 하며, 종결(MATCHED/MISMATCHED) 이후의
 * 번복은 새 서류 첨부로만 한다. public setter 없음 — 재구성은 빌더(영속 전용).
 */
public class CollateralDocument {

    private Long id;
    private final Long securedLoanId;
    private final Long collateralId;
    private final Long uploadedBy;           // JWT 주체에서 파생된 업로더 userId
    private final String fileName;
    private final String contentType;
    private final String fileHash;           // SHA-256 — (securedLoanId, fileHash) 멱등 키
    private final Long sizeBytes;
    private final ExtractedCollateralDocument extracted;
    private final String ocrModel;           // 감사·재현용 모델 식별자
    private CollateralDocumentStatus status;
    private String matchNote;
    private Long reviewedBy;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private CollateralDocument(Builder b) {
        this.id = b.id;
        this.securedLoanId = b.securedLoanId;
        this.collateralId = b.collateralId;
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

    /** OCR 추출 직후의 서류를 만든다 — 아직 대사 전(EXTRACTED). */
    public static CollateralDocument extracted(Long securedLoanId, Long collateralId, Long uploadedBy,
                                               String fileName, String contentType, String fileHash,
                                               Long sizeBytes, ExtractedCollateralDocument extracted,
                                               String ocrModel, LocalDateTime now) {
        requirePositive(securedLoanId, "securedLoanId");
        requirePositive(collateralId, "collateralId");
        requirePositive(uploadedBy, "uploadedBy");
        requireText(fileName, "파일명");
        requireText(contentType, "콘텐츠 타입");
        requireText(fileHash, "파일 해시");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new LoanInvariantViolationException("파일 크기는 양수여야 합니다: " + sizeBytes);
        }
        if (extracted == null) {
            throw new LoanInvariantViolationException("OCR 추출 결과는 필수입니다");
        }
        requireText(ocrModel, "OCR 모델명");
        Objects.requireNonNull(now, "now");
        Builder b = new Builder();
        b.securedLoanId = securedLoanId;
        b.collateralId = collateralId;
        b.uploadedBy = uploadedBy;
        b.fileName = fileName.trim();
        b.contentType = contentType.trim();
        b.fileHash = fileHash.trim();
        b.sizeBytes = sizeBytes;
        b.extracted = extracted;
        b.ocrModel = ocrModel.trim();
        b.status = CollateralDocumentStatus.EXTRACTED;
        b.createdAt = now;
        b.updatedAt = now;
        return new CollateralDocument(b);
    }

    /** 자동 대사 판정 적용 — EXTRACTED 에서만 가능. */
    public void applyDecision(CollateralDocumentMatchDecision decision, LocalDateTime now) {
        Objects.requireNonNull(decision, "decision");
        transitionTo(decision.status(), now);
        this.matchNote = decision.note();
    }

    /** 운영자 리뷰 확정 — NEEDS_REVIEW → MATCHED. */
    public void reviewMatch(Long reviewerId, String note, LocalDateTime now) {
        review(CollateralDocumentStatus.MATCHED, reviewerId, note, now);
    }

    /** 운영자 리뷰 반려 — NEEDS_REVIEW → MISMATCHED. */
    public void reviewMismatch(Long reviewerId, String note, LocalDateTime now) {
        review(CollateralDocumentStatus.MISMATCHED, reviewerId, note, now);
    }

    private void review(CollateralDocumentStatus next, Long reviewerId, String note, LocalDateTime now) {
        if (status != CollateralDocumentStatus.NEEDS_REVIEW) {
            throw new LoanInvariantViolationException(
                    "운영자 리뷰는 NEEDS_REVIEW 에서만 가능합니다: 현재 상태=" + status);
        }
        requirePositive(reviewerId, "reviewerId");
        transitionTo(next, now);
        this.reviewedBy = reviewerId;
        this.matchNote = note;
    }

    private void transitionTo(CollateralDocumentStatus next, LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        if (!status.canTransitionTo(next)) {
            throw new LoanInvariantViolationException("담보서류 상태 전이 불가: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new LoanInvariantViolationException(label + "은(는) 필수입니다");
        }
    }

    private static void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new LoanInvariantViolationException(label + "은(는) 양수여야 합니다: " + value);
        }
    }

    // ── getters ──

    public Long getId() { return id; }
    public Long getSecuredLoanId() { return securedLoanId; }
    public Long getCollateralId() { return collateralId; }
    public Long getUploadedBy() { return uploadedBy; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getFileHash() { return fileHash; }
    public Long getSizeBytes() { return sizeBytes; }
    public ExtractedCollateralDocument getExtracted() { return extracted; }
    public String getOcrModel() { return ocrModel; }
    public CollateralDocumentStatus getStatus() { return status; }
    public String getMatchNote() { return matchNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 */
    public static class Builder {
        private Long id;
        private Long securedLoanId;
        private Long collateralId;
        private Long uploadedBy;
        private String fileName;
        private String contentType;
        private String fileHash;
        private Long sizeBytes;
        private ExtractedCollateralDocument extracted;
        private String ocrModel;
        private CollateralDocumentStatus status;
        private String matchNote;
        private Long reviewedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder securedLoanId(Long v) { this.securedLoanId = v; return this; }
        public Builder collateralId(Long v) { this.collateralId = v; return this; }
        public Builder uploadedBy(Long v) { this.uploadedBy = v; return this; }
        public Builder fileName(String v) { this.fileName = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder fileHash(String v) { this.fileHash = v; return this; }
        public Builder sizeBytes(Long v) { this.sizeBytes = v; return this; }
        public Builder extracted(ExtractedCollateralDocument v) { this.extracted = v; return this; }
        public Builder ocrModel(String v) { this.ocrModel = v; return this; }
        public Builder status(CollateralDocumentStatus v) { this.status = v; return this; }
        public Builder matchNote(String v) { this.matchNote = v; return this; }
        public Builder reviewedBy(Long v) { this.reviewedBy = v; return this; }
        public Builder createdAt(LocalDateTime v) { this.createdAt = v; return this; }
        public Builder updatedAt(LocalDateTime v) { this.updatedAt = v; return this; }
        public CollateralDocument build() { return new CollateralDocument(this); }
    }
}
