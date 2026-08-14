package github.lms.lemuel.card.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 영수증 애그리거트 (순수 POJO — 프레임워크 의존 0, ADR 0036).
 *
 * <p>업로드된 영수증 1건의 OCR 추출 결과와 대사 상태를 소유한다. 멱등 키는
 * {@code (reportId, fileHash)}(영속 UNIQUE 와 결합) — 같은 파일을 다시 올려도 새 행이 생기지 않고
 * AI OCR 재호출도 이 키에서 차단된다(tax 스캔과 동일한 비용 방어).
 *
 * <p>상태 전이는 {@link ExpenseReceiptStatus} 전이표로만 하며, 종결(MATCHED/MISMATCHED) 이후의
 * 번복은 새 영수증 첨부로만 한다. public setter 없음 — 재구성은 빌더(영속 전용).
 */
public class ExpenseReceipt {

    private Long id;
    private final String reportId;           // 지출보고서 자연키 참조
    private final String captureId;          // 매입 참조 — 3자 대사의 나머지 축
    private final Long organizationId;
    private final Long uploaderUserId;       // JWT 주체에서 파생된 업로더 (보고서 holder 와 대조됨)
    private final String fileName;
    private final String contentType;
    private final String fileHash;           // SHA-256 — (reportId, fileHash) 멱등 키
    private final Long sizeBytes;
    private final ExtractedReceipt extracted;
    private final String ocrModel;           // 감사·재현용 모델 식별자
    private ExpenseReceiptStatus status;
    private String matchNote;
    private Long reviewedBy;
    private final Instant createdAt;
    private Instant updatedAt;

    private ExpenseReceipt(Builder b) {
        this.id = b.id;
        this.reportId = b.reportId;
        this.captureId = b.captureId;
        this.organizationId = b.organizationId;
        this.uploaderUserId = b.uploaderUserId;
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

    /** OCR 추출 직후의 영수증을 만든다 — 아직 대사 전(EXTRACTED). */
    public static ExpenseReceipt extracted(String reportId, String captureId, Long organizationId,
                                           Long uploaderUserId, String fileName, String contentType,
                                           String fileHash, Long sizeBytes, ExtractedReceipt extracted,
                                           String ocrModel, Instant now) {
        requireText(reportId, "reportId");
        requireText(captureId, "captureId");
        requirePositive(organizationId, "organizationId");
        requirePositive(uploaderUserId, "uploaderUserId");
        requireText(fileName, "파일명");
        requireText(contentType, "콘텐츠 타입");
        requireText(fileHash, "파일 해시");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new IllegalArgumentException("파일 크기는 양수여야 합니다: " + sizeBytes);
        }
        if (extracted == null) {
            throw new IllegalArgumentException("OCR 추출 결과는 필수입니다");
        }
        requireText(ocrModel, "OCR 모델명");
        Objects.requireNonNull(now, "now");
        Builder b = new Builder();
        b.reportId = reportId.trim();
        b.captureId = captureId.trim();
        b.organizationId = organizationId;
        b.uploaderUserId = uploaderUserId;
        b.fileName = fileName.trim();
        b.contentType = contentType.trim();
        b.fileHash = fileHash.trim();
        b.sizeBytes = sizeBytes;
        b.extracted = extracted;
        b.ocrModel = ocrModel.trim();
        b.status = ExpenseReceiptStatus.EXTRACTED;
        b.createdAt = now;
        b.updatedAt = now;
        return new ExpenseReceipt(b);
    }

    /** 자동 대사 판정 적용 — EXTRACTED 에서만 가능. */
    public void applyDecision(ReceiptMatchDecision decision, Instant now) {
        Objects.requireNonNull(decision, "decision");
        transitionTo(decision.status(), now);
        this.matchNote = decision.note();
    }

    /** 관리자 리뷰 확정 — NEEDS_REVIEW → MATCHED. */
    public void reviewMatch(Long reviewerId, String note, Instant now) {
        review(ExpenseReceiptStatus.MATCHED, reviewerId, note, now);
    }

    /** 관리자 리뷰 반려 — NEEDS_REVIEW → MISMATCHED. */
    public void reviewMismatch(Long reviewerId, String note, Instant now) {
        review(ExpenseReceiptStatus.MISMATCHED, reviewerId, note, now);
    }

    private void review(ExpenseReceiptStatus next, Long reviewerId, String note, Instant now) {
        if (status != ExpenseReceiptStatus.NEEDS_REVIEW) {
            throw new IllegalStateException(
                    "관리자 리뷰는 NEEDS_REVIEW 에서만 가능합니다: 현재 상태=" + status);
        }
        requirePositive(reviewerId, "reviewerId");
        transitionTo(next, now);
        this.reviewedBy = reviewerId;
        this.matchNote = note;
    }

    private void transitionTo(ExpenseReceiptStatus next, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("영수증 상태 전이 불가: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "은(는) 필수입니다");
        }
    }

    private static void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(label + "은(는) 양수여야 합니다: " + value);
        }
    }

    // ── getters ──

    public Long getId() { return id; }
    public String getReportId() { return reportId; }
    public String getCaptureId() { return captureId; }
    public Long getOrganizationId() { return organizationId; }
    public Long getUploaderUserId() { return uploaderUserId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public String getFileHash() { return fileHash; }
    public Long getSizeBytes() { return sizeBytes; }
    public ExtractedReceipt getExtracted() { return extracted; }
    public String getOcrModel() { return ocrModel; }
    public ExpenseReceiptStatus getStatus() { return status; }
    public String getMatchNote() { return matchNote; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 */
    public static class Builder {
        private Long id;
        private String reportId;
        private String captureId;
        private Long organizationId;
        private Long uploaderUserId;
        private String fileName;
        private String contentType;
        private String fileHash;
        private Long sizeBytes;
        private ExtractedReceipt extracted;
        private String ocrModel;
        private ExpenseReceiptStatus status;
        private String matchNote;
        private Long reviewedBy;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder reportId(String v) { this.reportId = v; return this; }
        public Builder captureId(String v) { this.captureId = v; return this; }
        public Builder organizationId(Long v) { this.organizationId = v; return this; }
        public Builder uploaderUserId(Long v) { this.uploaderUserId = v; return this; }
        public Builder fileName(String v) { this.fileName = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder fileHash(String v) { this.fileHash = v; return this; }
        public Builder sizeBytes(Long v) { this.sizeBytes = v; return this; }
        public Builder extracted(ExtractedReceipt v) { this.extracted = v; return this; }
        public Builder ocrModel(String v) { this.ocrModel = v; return this; }
        public Builder status(ExpenseReceiptStatus v) { this.status = v; return this; }
        public Builder matchNote(String v) { this.matchNote = v; return this; }
        public Builder reviewedBy(Long v) { this.reviewedBy = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public ExpenseReceipt build() { return new ExpenseReceipt(this); }
    }
}
