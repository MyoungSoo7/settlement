package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.SettlementViewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "seller_settlement_view")
public class SellerSettlementViewJpaEntity {

    /** settlement 측 정산 ID (이벤트로 수신 — 생성 전략 없음, 멱등 UPSERT 키). */
    @Id
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementViewStatus status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SellerSettlementViewJpaEntity() { }

    public SellerSettlementViewJpaEntity(Long settlementId, Long sellerId, BigDecimal amount,
                                         LocalDate dueDate, SettlementViewStatus status,
                                         LocalDateTime updatedAt) {
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.dueDate = dueDate;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
    public SettlementViewStatus getStatus() { return status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
