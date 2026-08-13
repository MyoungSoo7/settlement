package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.payout.adapter.out.persistence.PayoutFieldEncryptionConverter;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 세금계산서 스캔 영속 엔티티. {@code (seller_id, file_hash)} UNIQUE 로 재업로드 멱등을 DB 가 보증한다.
 *
 * <p>사업자등록번호는 {@link PayoutFieldEncryptionConverter}(AES-GCM enc:v1)로 앱단 암호화해 저장한다
 * — {@code SellerTaxProfileJpaEntity} 와 동일 스킴·동일 키(PAYOUT_ENC_KEY).
 */
@Entity
@Table(name = "tax_invoice_scans")
public class TaxInvoiceScanJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaxInvoiceScanStatus status;

    @Convert(converter = PayoutFieldEncryptionConverter.class)
    @Column(name = "supplier_business_no_enc", columnDefinition = "text")
    private String supplierBusinessNo;

    @Convert(converter = PayoutFieldEncryptionConverter.class)
    @Column(name = "buyer_business_no_enc", columnDefinition = "text")
    private String buyerBusinessNo;

    @Column(name = "written_date", nullable = false)
    private LocalDate writtenDate;

    @Column(name = "supply_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal supplyAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "approval_number", length = 64)
    private String approvalNumber;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "ocr_model", nullable = false, length = 64)
    private String ocrModel;

    @Column(name = "linked_tax_invoice_id")
    private Long linkedTaxInvoiceId;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected TaxInvoiceScanJpaEntity() {
    }

    TaxInvoiceScanJpaEntity(Long id, Long sellerId, String fileName, String contentType, String fileHash,
                            Long sizeBytes, TaxInvoiceScanStatus status, String supplierBusinessNo,
                            String buyerBusinessNo, LocalDate writtenDate, BigDecimal supplyAmount,
                            BigDecimal taxAmount, BigDecimal totalAmount, String approvalNumber,
                            BigDecimal confidence, String ocrModel, Long linkedTaxInvoiceId,
                            String reviewNote, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileHash = fileHash;
        this.sizeBytes = sizeBytes;
        this.status = status;
        this.supplierBusinessNo = supplierBusinessNo;
        this.buyerBusinessNo = buyerBusinessNo;
        this.writtenDate = writtenDate;
        this.supplyAmount = supplyAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.approvalNumber = approvalNumber;
        this.confidence = confidence;
        this.ocrModel = ocrModel;
        this.linkedTaxInvoiceId = linkedTaxInvoiceId;
        this.reviewNote = reviewNote;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public TaxInvoiceScanStatus getStatus() {
        return status;
    }

    public String getSupplierBusinessNo() {
        return supplierBusinessNo;
    }

    public String getBuyerBusinessNo() {
        return buyerBusinessNo;
    }

    public LocalDate getWrittenDate() {
        return writtenDate;
    }

    public BigDecimal getSupplyAmount() {
        return supplyAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getApprovalNumber() {
        return approvalNumber;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getOcrModel() {
        return ocrModel;
    }

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
