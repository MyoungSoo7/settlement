package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.DepositProofStatus;
import github.lms.lemuel.deposit.domain.ExtractedTransferProof;
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
 * deposit_proofs 테이블 매핑.
 *
 * <p>(seller_id, reference_type, reference_id, file_hash) UNIQUE 가 멱등 최후 방어선.
 * 파일 본문(content)은 불변 — 상태 변경 업데이트에서 다시 쓰지 않는다.
 */
@Entity
@Table(name = "deposit_proofs", schema = "opslab")
public class DepositProofJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "reference_type", nullable = false, length = 40)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

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

    @Column(name = "sender_name", length = 100)
    private String senderName;

    @Column(name = "transfer_date")
    private LocalDate transferDate;

    @Column(name = "transfer_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal transferAmount;

    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "ocr_model", nullable = false, length = 100)
    private String ocrModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositProofStatus status;

    @Column(name = "match_note", length = 500)
    private String matchNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DepositProofJpaEntity() {
    }

    static DepositProofJpaEntity fromDomain(DepositProof proof, byte[] content) {
        DepositProofJpaEntity e = new DepositProofJpaEntity();
        e.id = proof.getId();
        e.sellerId = proof.getSellerId();
        e.referenceType = proof.getReferenceType();
        e.referenceId = proof.getReferenceId();
        e.uploadedBy = proof.getUploadedBy();
        e.fileName = proof.getFileName();
        e.contentType = proof.getContentType();
        e.fileHash = proof.getFileHash();
        e.sizeBytes = proof.getSizeBytes();
        e.content = content;
        e.senderName = proof.getExtracted().senderName();
        e.transferDate = proof.getExtracted().transferDate();
        e.transferAmount = proof.getExtracted().transferAmount();
        e.confidence = proof.getExtracted().confidence();
        e.ocrModel = proof.getOcrModel();
        e.status = proof.getStatus();
        e.matchNote = proof.getMatchNote();
        e.reviewedBy = proof.getReviewedBy();
        e.createdAt = proof.getCreatedAt();
        e.updatedAt = proof.getUpdatedAt();
        return e;
    }

    /** 상태 변경(지연 대사·리뷰 종결)만 반영 — 파일 본문·추출값은 불변. */
    void applyStateFrom(DepositProof proof) {
        this.status = proof.getStatus();
        this.matchNote = proof.getMatchNote();
        this.reviewedBy = proof.getReviewedBy();
        this.updatedAt = proof.getUpdatedAt();
    }

    DepositProof toDomain() {
        return DepositProof.builder()
                .id(id)
                .sellerId(sellerId)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .uploadedBy(uploadedBy)
                .fileName(fileName)
                .contentType(contentType)
                .fileHash(fileHash)
                .sizeBytes(sizeBytes)
                .extracted(new ExtractedTransferProof(senderName, transferDate, transferAmount, confidence))
                .ocrModel(ocrModel)
                .status(status)
                .matchNote(matchNote)
                .reviewedBy(reviewedBy)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
