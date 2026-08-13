package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.card.domain.ExtractedReceipt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * expense_receipts 테이블 매핑 (V10).
 *
 * <p>(report_id, file_hash) UNIQUE 가 멱등 최후 방어선. 파일 본문(content)은 불변 — 상태 변경
 * 업데이트에서 다시 쓰지 않는다.
 */
@Entity
@Table(name = "expense_receipts")
public class ExpenseReceiptJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false, length = 64)
    private String reportId;

    @Column(name = "capture_id", nullable = false, length = 64)
    private String captureId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "uploader_user_id", nullable = false)
    private Long uploaderUserId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "merchant_name", length = 200)
    private String merchantName;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "ocr_model", nullable = false, length = 100)
    private String ocrModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExpenseReceiptStatus status;

    @Column(name = "match_note", length = 500)
    private String matchNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExpenseReceiptJpaEntity() {
    }

    static ExpenseReceiptJpaEntity fromDomain(ExpenseReceipt receipt, byte[] content) {
        ExpenseReceiptJpaEntity e = new ExpenseReceiptJpaEntity();
        e.id = receipt.getId();
        e.reportId = receipt.getReportId();
        e.captureId = receipt.getCaptureId();
        e.organizationId = receipt.getOrganizationId();
        e.uploaderUserId = receipt.getUploaderUserId();
        e.fileName = receipt.getFileName();
        e.contentType = receipt.getContentType();
        e.fileHash = receipt.getFileHash();
        e.sizeBytes = receipt.getSizeBytes();
        e.content = content;
        e.merchantName = receipt.getExtracted().merchantName();
        e.transactionDate = receipt.getExtracted().transactionDate();
        e.totalAmount = receipt.getExtracted().totalAmount();
        e.confidence = receipt.getExtracted().confidence();
        e.ocrModel = receipt.getOcrModel();
        e.status = receipt.getStatus();
        e.matchNote = receipt.getMatchNote();
        e.reviewedBy = receipt.getReviewedBy();
        e.createdAt = receipt.getCreatedAt();
        e.updatedAt = receipt.getUpdatedAt();
        return e;
    }

    /** 상태 변경(리뷰 종결)만 반영 — 파일 본문·추출값은 불변. */
    void applyStateFrom(ExpenseReceipt receipt) {
        this.status = receipt.getStatus();
        this.matchNote = receipt.getMatchNote();
        this.reviewedBy = receipt.getReviewedBy();
        this.updatedAt = receipt.getUpdatedAt();
    }

    ExpenseReceipt toDomain() {
        return ExpenseReceipt.builder()
                .id(id)
                .reportId(reportId)
                .captureId(captureId)
                .organizationId(organizationId)
                .uploaderUserId(uploaderUserId)
                .fileName(fileName)
                .contentType(contentType)
                .fileHash(fileHash)
                .sizeBytes(sizeBytes)
                .extracted(new ExtractedReceipt(merchantName, transactionDate, totalAmount, confidence))
                .ocrModel(ocrModel)
                .status(status)
                .matchNote(matchNote)
                .reviewedBy(reviewedBy)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    Long id() {
        return id;
    }
}
