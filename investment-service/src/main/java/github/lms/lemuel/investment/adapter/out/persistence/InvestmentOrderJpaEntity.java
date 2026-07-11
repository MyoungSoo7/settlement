package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "investment_orders")
public class InvestmentOrderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "score_at_order", nullable = false)
    private int scoreAtOrder;

    @Column(name = "grade_at_order", nullable = false, length = 3)
    private String gradeAtOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvestmentOrderStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected InvestmentOrderJpaEntity() { }

    public InvestmentOrderJpaEntity(Long id, Long sellerId, String stockCode, BigDecimal amount,
                                    int scoreAtOrder, String gradeAtOrder, InvestmentOrderStatus status,
                                    LocalDateTime createdAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.stockCode = stockCode;
        this.amount = amount;
        this.scoreAtOrder = scoreAtOrder;
        this.gradeAtOrder = gradeAtOrder;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public String getStockCode() { return stockCode; }
    public BigDecimal getAmount() { return amount; }
    public int getScoreAtOrder() { return scoreAtOrder; }
    public String getGradeAtOrder() { return gradeAtOrder; }
    public InvestmentOrderStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
