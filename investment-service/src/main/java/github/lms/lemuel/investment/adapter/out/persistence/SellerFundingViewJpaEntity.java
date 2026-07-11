package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.FundingViewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "seller_funding_view")
public class SellerFundingViewJpaEntity {

    /** settlement 측 정산 ID (이벤트로 수신 — 생성 전략 없음, 멱등 UPSERT 키). */
    @Id
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FundingViewStatus status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SellerFundingViewJpaEntity() { }

    public SellerFundingViewJpaEntity(Long settlementId, Long sellerId, BigDecimal amount,
                                      FundingViewStatus status, LocalDateTime updatedAt) {
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public FundingViewStatus getStatus() { return status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
