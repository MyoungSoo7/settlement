package github.lms.lemuel.tax.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 세금계산서 영속 엔티티. settlement_id·issue_number UNIQUE 로 정산 1건당 1계산서(멱등)를 강제한다.
 */
@Entity
@Table(name = "tax_invoices")
public class TaxInvoiceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "supply_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal supplyAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "issue_number", nullable = false, length = 40)
    private String issueNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TaxInvoiceJpaEntity() {
    }

    public TaxInvoiceJpaEntity(Long id, Long settlementId, Long sellerId, BigDecimal supplyAmount,
                               BigDecimal taxAmount, BigDecimal totalAmount, LocalDate issueDate,
                               String issueNumber, LocalDateTime createdAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.supplyAmount = supplyAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.issueDate = issueDate;
        this.issueNumber = issueNumber;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public Long getSellerId() {
        return sellerId;
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

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public String getIssueNumber() {
        return issueNumber;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
