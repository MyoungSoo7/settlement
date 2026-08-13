package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;
import github.lms.lemuel.insurance.domain.ExtractedApplicationForm;
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
import java.util.UUID;

/**
 * application_documents 테이블 매핑 (V11).
 *
 * <p>(application_id, file_hash) UNIQUE 가 멱등 최후 방어선. 파일 본문(content)은 불변 —
 * 상태 변경 업데이트에서 다시 쓰지 않는다.
 */
@Entity
@Table(name = "application_documents", schema = "opslab")
public class ApplicationDocumentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "uploaded_by", nullable = false, length = 64)
    private String uploadedBy;

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

    @Column(name = "contractor_name", length = 100)
    private String contractorName;

    @Column(name = "insured_name", length = 100)
    private String insuredName;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "annual_premium", nullable = false, precision = 19, scale = 2)
    private BigDecimal annualPremium;

    @Column(name = "coverage_amount", precision = 19, scale = 2)
    private BigDecimal coverageAmount;

    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "ocr_model", nullable = false, length = 100)
    private String ocrModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationDocumentStatus status;

    @Column(name = "match_note", length = 500)
    private String matchNote;

    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApplicationDocumentJpaEntity() {
    }

    static ApplicationDocumentJpaEntity fromDomain(ApplicationDocument document, byte[] content) {
        ApplicationDocumentJpaEntity e = new ApplicationDocumentJpaEntity();
        e.id = document.getId();
        e.applicationId = UUID.fromString(document.getApplicationId());
        e.uploadedBy = document.getUploadedBy();
        e.fileName = document.getFileName();
        e.contentType = document.getContentType();
        e.fileHash = document.getFileHash();
        e.sizeBytes = document.getSizeBytes();
        e.content = content;
        e.contractorName = document.getExtracted().contractorName();
        e.insuredName = document.getExtracted().insuredName();
        e.productName = document.getExtracted().productName();
        e.applicationDate = document.getExtracted().applicationDate();
        e.annualPremium = document.getExtracted().annualPremium();
        e.coverageAmount = document.getExtracted().coverageAmount();
        e.confidence = document.getExtracted().confidence();
        e.ocrModel = document.getOcrModel();
        e.status = document.getStatus();
        e.matchNote = document.getMatchNote();
        e.reviewedBy = document.getReviewedBy();
        e.createdAt = document.getCreatedAt();
        e.updatedAt = document.getUpdatedAt();
        return e;
    }

    /** 상태 변경(리뷰 종결)만 반영 — 파일 본문·추출값은 불변. */
    void applyStateFrom(ApplicationDocument document) {
        this.status = document.getStatus();
        this.matchNote = document.getMatchNote();
        this.reviewedBy = document.getReviewedBy();
        this.updatedAt = document.getUpdatedAt();
    }

    ApplicationDocument toDomain() {
        return ApplicationDocument.builder()
                .id(id)
                .applicationId(applicationId.toString())
                .uploadedBy(uploadedBy)
                .fileName(fileName)
                .contentType(contentType)
                .fileHash(fileHash)
                .sizeBytes(sizeBytes)
                .extracted(new ExtractedApplicationForm(contractorName, insuredName, productName,
                        applicationDate, annualPremium, coverageAmount, confidence))
                .ocrModel(ocrModel)
                .status(status)
                .matchNote(matchNote)
                .reviewedBy(reviewedBy)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
