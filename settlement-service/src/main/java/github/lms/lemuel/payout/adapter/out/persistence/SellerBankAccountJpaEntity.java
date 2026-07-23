package github.lms.lemuel.payout.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 셀러 지급 계좌 레지스트리 영속 엔티티.
 *
 * <p>PK 는 {@code seller_id}(assigned, 셀러당 1행 = upsert 정본). 계좌번호는
 * {@link PayoutFieldEncryptionConverter}(AES-GCM enc:v1) 로 앱단 암호화되어 {@code account_number_enc}
 * (text) 에 저장된다 — Payout 지급계좌 암호화와 동일 스킴·동일 키. 예금주명은 평문 컬럼.
 */
@Entity
@Table(name = "seller_bank_accounts")
public class SellerBankAccountJpaEntity {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    @Convert(converter = PayoutFieldEncryptionConverter.class)
    @Column(name = "account_number_enc", nullable = false, columnDefinition = "text")
    private String accountNumber;

    @Column(name = "account_holder", nullable = false, length = 100)
    private String accountHolder;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SellerBankAccountJpaEntity() {
    }

    public SellerBankAccountJpaEntity(Long sellerId, String bankCode, String accountNumber,
                                      String accountHolder, LocalDateTime updatedAt) {
        this.sellerId = sellerId;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.updatedAt = updatedAt;
    }

    public Long getSellerId() { return sellerId; }
    public String getBankCode() { return bankCode; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountHolder() { return accountHolder; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** 계좌 정정 반영 — 정정된 계좌 필드·갱신시각을 덮어쓴다. */
    public void applyChange(String bankCode, String accountNumber, String accountHolder,
                            LocalDateTime updatedAt) {
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.updatedAt = updatedAt;
    }
}
