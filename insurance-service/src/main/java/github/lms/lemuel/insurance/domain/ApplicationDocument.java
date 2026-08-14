package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;
import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentTransitionException;

import java.time.Instant;
import java.util.Objects;

/**
 * 청약서류 애그리거트 (순수 POJO — 프레임워크 의존 0, ADR 0036 확산).
 *
 * <p>업로드된 청약서 스캔 1건의 OCR 추출 결과와 대사 상태를 소유한다. 멱등 키는
 * {@code (applicationId, fileHash)}(영속 UNIQUE 와 결합) — 같은 파일을 다시 올려도 새 행이 생기지
 * 않고 AI OCR 재호출도 이 키에서 차단된다(tax 스캔·card 영수증과 동일한 비용 방어).
 *
 * <p>상태 전이는 {@link ApplicationDocumentStatus} 전이표로만 하며, 종결(MATCHED/MISMATCHED) 이후의
 * 번복은 새 서류 첨부로만 한다. public setter 없음 — 재구성은 빌더(영속 전용).
 */
public class ApplicationDocument {

    private Long id;
    private final String applicationId;      // 청약 자연키(UUID 문자열) 참조
    private final String uploadedBy;         // JWT 주체에서 파생된 업로더 (FcIdentity)
    private final String fileName;
    private final String contentType;
    private final String fileHash;           // SHA-256 — (applicationId, fileHash) 멱등 키
    private final Long sizeBytes;
    private final ExtractedApplicationForm extracted;
    private final String ocrModel;           // 감사·재현용 모델 식별자
    private ApplicationDocumentStatus status;
    private String matchNote;
    private String reviewedBy;
    private final Instant createdAt;
    private Instant updatedAt;

    private ApplicationDocument(Builder b) {
        this.id = b.id;
        this.applicationId = b.applicationId;
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
    public static ApplicationDocument extracted(String applicationId, String uploadedBy,
                                                String fileName, String contentType, String fileHash,
                                                Long sizeBytes, ExtractedApplicationForm extracted,
                                                String ocrModel, Instant now) {
        requireText(applicationId, "applicationId");
        requireText(uploadedBy, "업로더 식별자");
        requireText(fileName, "파일명");
        requireText(contentType, "콘텐츠 타입");
        requireText(fileHash, "파일 해시");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new InvalidApplicationDocumentException("파일 크기는 양수여야 합니다: " + sizeBytes);
        }
        if (extracted == null) {
            throw new InvalidApplicationDocumentException("OCR 추출 결과는 필수입니다");
        }
        requireText(ocrModel, "OCR 모델명");
        Objects.requireNonNull(now, "now");
        Builder b = new Builder();
        b.applicationId = applicationId.trim();
        b.uploadedBy = uploadedBy.trim();
        b.fileName = fileName.trim();
        b.contentType = contentType.trim();
        b.fileHash = fileHash.trim();
        b.sizeBytes = sizeBytes;
        b.extracted = extracted;
        b.ocrModel = ocrModel.trim();
        b.status = ApplicationDocumentStatus.EXTRACTED;
        b.createdAt = now;
        b.updatedAt = now;
        return new ApplicationDocument(b);
    }

    /** 자동 대사 판정 적용 — EXTRACTED 에서만 가능. */
    public void applyDecision(DocumentMatchDecision decision, Instant now) {
        Objects.requireNonNull(decision, "decision");
        transitionTo(decision.status(), now);
        this.matchNote = decision.note();
    }

    /** 관리자 리뷰 확정 — NEEDS_REVIEW → MATCHED. */
    public void reviewMatch(String reviewerId, String note, Instant now) {
        review(ApplicationDocumentStatus.MATCHED, reviewerId, note, now);
    }

    /** 관리자 리뷰 반려 — NEEDS_REVIEW → MISMATCHED. */
    public void reviewMismatch(String reviewerId, String note, Instant now) {
        review(ApplicationDocumentStatus.MISMATCHED, reviewerId, note, now);
    }

    private void review(ApplicationDocumentStatus next, String reviewerId, String note, Instant now) {
        if (status != ApplicationDocumentStatus.NEEDS_REVIEW) {
            throw new InvalidApplicationDocumentTransitionException(
                    "관리자 리뷰는 NEEDS_REVIEW 에서만 가능합니다: 현재 상태=" + status);
        }
        requireText(reviewerId, "리뷰어 식별자");
        transitionTo(next, now);
        this.reviewedBy = reviewerId.trim();
        this.matchNote = note;
    }

    private void transitionTo(ApplicationDocumentStatus next, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!status.canTransitionTo(next)) {
            throw new InvalidApplicationDocumentTransitionException(
                    "청약서류 상태 전이 불가: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new InvalidApplicationDocumentException(label + "은(는) 필수입니다");
        }
    }

    // ── getters ──

    public Long getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public String getUploadedBy() { return uploadedBy; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getFileHash() { return fileHash; }
    public Long getSizeBytes() { return sizeBytes; }
    public ExtractedApplicationForm getExtracted() { return extracted; }
    public String getOcrModel() { return ocrModel; }
    public ApplicationDocumentStatus getStatus() { return status; }
    public String getMatchNote() { return matchNote; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 */
    public static class Builder {
        private Long id;
        private String applicationId;
        private String uploadedBy;
        private String fileName;
        private String contentType;
        private String fileHash;
        private Long sizeBytes;
        private ExtractedApplicationForm extracted;
        private String ocrModel;
        private ApplicationDocumentStatus status;
        private String matchNote;
        private String reviewedBy;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder applicationId(String v) { this.applicationId = v; return this; }
        public Builder uploadedBy(String v) { this.uploadedBy = v; return this; }
        public Builder fileName(String v) { this.fileName = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder fileHash(String v) { this.fileHash = v; return this; }
        public Builder sizeBytes(Long v) { this.sizeBytes = v; return this; }
        public Builder extracted(ExtractedApplicationForm v) { this.extracted = v; return this; }
        public Builder ocrModel(String v) { this.ocrModel = v; return this; }
        public Builder status(ApplicationDocumentStatus v) { this.status = v; return this; }
        public Builder matchNote(String v) { this.matchNote = v; return this; }
        public Builder reviewedBy(String v) { this.reviewedBy = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public ApplicationDocument build() { return new ApplicationDocument(this); }
    }
}
