package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.CollateralDocumentStatus;
import github.lms.lemuel.loan.domain.ExtractedCollateralDocument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * collateral_documents 테이블 매핑.
 *
 * <p>(secured_loan_id, file_hash) UNIQUE 가 멱등 최후 방어선. 파일 본문(content)은 불변 —
 * 상태 변경 업데이트에서 다시 쓰지 않는다.
 */
@Entity
@Table(name = "collateral_documents")
public class CollateralDocumentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "secured_loan_id", nullable = false)
    private Long securedLoanId;

    @Column(name = "collateral_id", nullable = false)
    private Long collateralId;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

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

    @Column(name = "owner_name", length = 100)
    private String ownerName;

    @Column(name = "location_text", length = 500)
    private String locationText;

    @Column(name = "appraised_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal appraisedValue;

    @Column(name = "senior_claim_amount", precision = 19, scale = 2)
    private BigDecimal seniorClaimAmount;

    @Column(name = "appraisal_date")
    private LocalDate appraisalDate;

    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "ocr_model", nullable = false, length = 100)
    private String ocrModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CollateralDocumentStatus status;

    @Column(name = "match_note", length = 500)
    private String matchNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CollateralDocumentJpaEntity() {
    }

    static CollateralDocumentJpaEntity fromDomain(CollateralDocument document, byte[] content) {
        CollateralDocumentJpaEntity e = new CollateralDocumentJpaEntity();
        e.id = document.getId();
        e.securedLoanId = document.getSecuredLoanId();
        e.collateralId = document.getCollateralId();
        e.uploadedBy = document.getUploadedBy();
        e.fileName = document.getFileName();
        e.contentType = document.getContentType();
        e.fileHash = document.getFileHash();
        e.sizeBytes = document.getSizeBytes();
        e.content = content;
        e.ownerName = document.getExtracted().ownerName();
        e.locationText = document.getExtracted().locationText();
        e.appraisedValue = document.getExtracted().appraisedValue();
        e.seniorClaimAmount = document.getExtracted().seniorClaimAmount();
        e.appraisalDate = document.getExtracted().appraisalDate();
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
    void applyStateFrom(CollateralDocument document) {
        this.status = document.getStatus();
        this.matchNote = document.getMatchNote();
        this.reviewedBy = document.getReviewedBy();
        this.updatedAt = document.getUpdatedAt();
    }

    CollateralDocument toDomain() {
        return CollateralDocument.builder()
                .id(id)
                .securedLoanId(securedLoanId)
                .collateralId(collateralId)
                .uploadedBy(uploadedBy)
                .fileName(fileName)
                .contentType(contentType)
                .fileHash(fileHash)
                .sizeBytes(sizeBytes)
                .extracted(new ExtractedCollateralDocument(ownerName, locationText, appraisedValue,
                        seniorClaimAmount, appraisalDate, confidence))
                .ocrModel(ocrModel)
                .status(status)
                .matchNote(matchNote)
                .reviewedBy(reviewedBy)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
