package github.lms.lemuel.tax.domain.scan;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import github.lms.lemuel.tax.domain.exception.TaxInvoiceScanStateException;

import java.time.OffsetDateTime;

/**
 * 세금계산서 스캔 애그리거트 — 업로드된 스캔본 1건의 OCR 추출 결과와 대사 상태를 소유한다.
 *
 * <p>멱등 키는 {@code (sellerId, fileHash)} 다 — 같은 파일을 다시 올려도 새 스캔이 생기지 않는다
 * (영속 UNIQUE 와 결합). AI OCR 호출은 비용이 있으므로 재호출도 이 키에서 차단된다.
 *
 * <p>상태 전이는 {@link TaxInvoiceScanStatus} 전이표로만 하며, 종결(MATCHED/REJECTED) 이후의 번복은
 * {@link TaxInvoiceScanStateException} 이다. public setter 없음 — 재구성은 {@link #rehydrate} 전용.
 */
public class TaxInvoiceScan {

    private Long id;
    private final Long sellerId;
    private final String fileName;
    private final String contentType;
    private final String fileHash;
    private final Long sizeBytes;
    private final ExtractedTaxInvoice extracted;
    private final String ocrModel;
    private TaxInvoiceScanStatus status;
    private Long linkedTaxInvoiceId;
    private String reviewNote;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private TaxInvoiceScan(Long id, Long sellerId, String fileName, String contentType, String fileHash,
                           Long sizeBytes, ExtractedTaxInvoice extracted, String ocrModel,
                           TaxInvoiceScanStatus status, Long linkedTaxInvoiceId, String reviewNote,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileHash = fileHash;
        this.sizeBytes = sizeBytes;
        this.extracted = extracted;
        this.ocrModel = ocrModel;
        this.status = status;
        this.linkedTaxInvoiceId = linkedTaxInvoiceId;
        this.reviewNote = reviewNote;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** OCR 추출 직후의 스캔을 만든다 — 아직 대사 전(EXTRACTED). */
    public static TaxInvoiceScan extracted(Long sellerId, String fileName, String contentType,
                                           String fileHash, Long sizeBytes, ExtractedTaxInvoice extracted,
                                           String ocrModel, OffsetDateTime now) {
        if (sellerId == null || sellerId <= 0) {
            throw new TaxInvariantViolationException("sellerId 는 양수여야 합니다: " + sellerId);
        }
        requireText(fileName, "파일명");
        requireText(contentType, "콘텐츠 타입");
        requireText(fileHash, "파일 해시");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new TaxInvariantViolationException("파일 크기는 양수여야 합니다: " + sizeBytes);
        }
        if (extracted == null) {
            throw new TaxInvariantViolationException("OCR 추출 결과는 필수입니다");
        }
        requireText(ocrModel, "OCR 모델명");
        if (now == null) {
            throw new TaxInvariantViolationException("생성 시각은 필수입니다");
        }
        return new TaxInvoiceScan(null, sellerId, fileName.trim(), contentType.trim(), fileHash.trim(),
                sizeBytes, extracted, ocrModel.trim(), TaxInvoiceScanStatus.EXTRACTED, null, null, now, now);
    }

    /** 영속 복원 전용 — 불변식 검사 없이 저장된 상태를 그대로 되살린다. */
    public static TaxInvoiceScan rehydrate(Long id, Long sellerId, String fileName, String contentType,
                                           String fileHash, Long sizeBytes, ExtractedTaxInvoice extracted,
                                           String ocrModel, TaxInvoiceScanStatus status,
                                           Long linkedTaxInvoiceId, String reviewNote,
                                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new TaxInvoiceScan(id, sellerId, fileName, contentType, fileHash, sizeBytes, extracted,
                ocrModel, status, linkedTaxInvoiceId, reviewNote, createdAt, updatedAt);
    }

    /** 대사 확정 — 발행 세금계산서와 금액이 전부 일치했다. */
    public void matchTo(Long taxInvoiceId, OffsetDateTime now) {
        if (taxInvoiceId == null) {
            throw new TaxInvariantViolationException("매칭할 세금계산서 식별자는 필수입니다");
        }
        transitionTo(TaxInvoiceScanStatus.MATCHED, now);
        this.linkedTaxInvoiceId = taxInvoiceId;
        this.reviewNote = null;
    }

    /** 후보는 찾았으나 금액이 어긋났다 — 후보 식별자는 조사 단서로 남긴다. */
    public void markMismatched(Long candidateTaxInvoiceId, String reason, OffsetDateTime now) {
        transitionTo(TaxInvoiceScanStatus.MISMATCHED, now);
        this.linkedTaxInvoiceId = candidateTaxInvoiceId;
        this.reviewNote = reason;
    }

    /** 대응하는 발행분을 찾지 못했다. */
    public void markUnmatched(String reason, OffsetDateTime now) {
        transitionTo(TaxInvoiceScanStatus.UNMATCHED, now);
        this.linkedTaxInvoiceId = null;
        this.reviewNote = reason;
    }

    /** 관리자 반려 — 종결이며 되돌릴 수 없다. */
    public void reject(String note, OffsetDateTime now) {
        transitionTo(TaxInvoiceScanStatus.REJECTED, now);
        this.reviewNote = note;
    }

    private void transitionTo(TaxInvoiceScanStatus next, OffsetDateTime now) {
        if (now == null) {
            throw new TaxInvariantViolationException("전이 시각은 필수입니다");
        }
        if (!status.canTransitionTo(next)) {
            throw new TaxInvoiceScanStateException(
                    "세금계산서 스캔 상태 전이 불가: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    /** 영속 후 DB 가 부여한 PK 를 1회만 주입(write-once). */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new TaxInvariantViolationException(label + "은(는) 필수입니다");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileHash() {
        return fileHash;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public ExtractedTaxInvoice getExtracted() {
        return extracted;
    }

    public String getOcrModel() {
        return ocrModel;
    }

    public TaxInvoiceScanStatus getStatus() {
        return status;
    }

    /** 대사가 지목한 발행 세금계산서 — MATCHED 면 확정, MISMATCHED 면 후보. */
    public Long getLinkedTaxInvoiceId() {
        return linkedTaxInvoiceId;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
