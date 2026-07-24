package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.payout.adapter.out.persistence.PayoutFieldEncryptionConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 셀러 세무 프로필 영속 엔티티.
 *
 * <p>PK 는 {@code seller_id}(assigned, 셀러당 1행 = upsert). 사업자등록번호는
 * {@link PayoutFieldEncryptionConverter}(AES-GCM enc:v1)로 앱단 암호화되어 {@code business_reg_no_enc}(text)에
 * 저장된다 — Payout 지급계좌 암호화와 동일 스킴·동일 키. 개인 셀러는 NULL.
 */
@Entity
@Table(name = "seller_tax_profiles")
public class SellerTaxProfileJpaEntity {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "tax_type", nullable = false, length = 16)
    private String taxType;

    @Convert(converter = PayoutFieldEncryptionConverter.class)
    @Column(name = "business_reg_no_enc", columnDefinition = "text")
    private String businessRegNo;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SellerTaxProfileJpaEntity() {
    }

    public SellerTaxProfileJpaEntity(Long sellerId, String taxType, String businessRegNo,
                                     LocalDateTime updatedAt) {
        this.sellerId = sellerId;
        this.taxType = taxType;
        this.businessRegNo = businessRegNo;
        this.updatedAt = updatedAt;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public String getTaxType() {
        return taxType;
    }

    public String getBusinessRegNo() {
        return businessRegNo;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** 세무유형·사업자등록번호 정정 반영. */
    public void applyChange(String taxType, String businessRegNo, LocalDateTime updatedAt) {
        this.taxType = taxType;
        this.businessRegNo = businessRegNo;
        this.updatedAt = updatedAt;
    }
}
