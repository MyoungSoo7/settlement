package github.lms.lemuel.market.adapter.out.persistence;

import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stocks")
public class StockJpaEntity {

    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    @Column(name = "isin", length = 12)
    private String isin;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false, length = 10)
    private Market market;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockJpaEntity() {
    }

    static StockJpaEntity fromDomain(Stock stock) {
        StockJpaEntity entity = new StockJpaEntity();
        entity.stockCode = stock.stockCode();
        entity.applyDomain(stock);
        return entity;
    }

    void applyDomain(Stock stock) {
        this.isin = stock.isin();
        this.name = stock.name();
        this.market = stock.market();
        this.updatedAt = stock.updatedAt() != null ? stock.updatedAt() : Instant.now();
    }

    Stock toDomain() {
        return new Stock(stockCode, isin, name, market, updatedAt);
    }
}
