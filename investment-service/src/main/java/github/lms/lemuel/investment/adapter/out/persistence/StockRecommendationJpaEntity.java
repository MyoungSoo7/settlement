package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.StockRecommendation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "stock_recommendations")
public class StockRecommendationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommended_date", nullable = false)
    private LocalDate recommendedDate;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 50)
    private String stockName;

    @Column(name = "sector", nullable = false, length = 30)
    private String sector;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "entry_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal entryPrice;

    @Column(name = "stop_loss_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal takeProfitPrice;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected StockRecommendationJpaEntity() { }

    private StockRecommendationJpaEntity(LocalDate recommendedDate, String stockCode, String stockName,
                                         String sector, String reason, BigDecimal entryPrice,
                                         BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                                         int displayOrder) {
        this.recommendedDate = recommendedDate;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.sector = sector;
        this.reason = reason;
        this.entryPrice = entryPrice;
        this.stopLossPrice = stopLossPrice;
        this.takeProfitPrice = takeProfitPrice;
        this.displayOrder = displayOrder;
    }

    /** 도메인 추천을 신규 저장 엔티티로 변환한다(id 는 INSERT 시 생성). */
    public static StockRecommendationJpaEntity fromDomain(StockRecommendation r) {
        return new StockRecommendationJpaEntity(r.recommendedDate(), r.stockCode(), r.stockName(),
                r.sector(), r.reason(), r.entryPrice(), r.stopLossPrice(), r.takeProfitPrice(),
                r.displayOrder());
    }

    public Long getId() { return id; }
    public LocalDate getRecommendedDate() { return recommendedDate; }
    public String getStockCode() { return stockCode; }
    public String getStockName() { return stockName; }
    public String getSector() { return sector; }
    public String getReason() { return reason; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public BigDecimal getTakeProfitPrice() { return takeProfitPrice; }
    public int getDisplayOrder() { return displayOrder; }
}
