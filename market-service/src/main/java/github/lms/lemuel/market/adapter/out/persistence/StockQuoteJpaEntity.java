package github.lms.lemuel.market.adapter.out.persistence;

import github.lms.lemuel.market.domain.StockQuote;
import github.lms.lemuel.market.domain.ValueSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "stock_quotes",
        uniqueConstraints = @UniqueConstraint(name = "uq_sq_stock_date",
                columnNames = {"stock_code", "base_date"}))
public class StockQuoteJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "close_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal closePrice;

    @Column(name = "open_price", precision = 15, scale = 2)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 15, scale = 2)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 15, scale = 2)
    private BigDecimal lowPrice;

    @Column(name = "prior_day_diff", precision = 15, scale = 2)
    private BigDecimal priorDayDiff;

    @Column(name = "fluctuation_rate", precision = 8, scale = 2)
    private BigDecimal fluctuationRate;

    @Column(name = "volume", precision = 20)
    private BigInteger volume;

    @Column(name = "trade_amount", precision = 24)
    private BigInteger tradeAmount;

    @Column(name = "listed_shares", precision = 20)
    private BigInteger listedShares;

    @Column(name = "market_cap", precision = 24)
    private BigInteger marketCap;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private ValueSource source;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    protected StockQuoteJpaEntity() {
    }

    static StockQuoteJpaEntity fromDomain(StockQuote quote) {
        StockQuoteJpaEntity entity = new StockQuoteJpaEntity();
        entity.stockCode = quote.stockCode();
        entity.baseDate = quote.baseDate();
        entity.applyDomain(quote);
        return entity;
    }

    void applyDomain(StockQuote quote) {
        this.closePrice = quote.closePrice();
        this.openPrice = quote.openPrice();
        this.highPrice = quote.highPrice();
        this.lowPrice = quote.lowPrice();
        this.priorDayDiff = quote.priorDayDiff();
        this.fluctuationRate = quote.fluctuationRate();
        this.volume = quote.volume();
        this.tradeAmount = quote.tradeAmount();
        this.listedShares = quote.listedShares();
        this.marketCap = quote.marketCap();
        this.source = quote.source();
        this.syncedAt = quote.syncedAt() != null ? quote.syncedAt() : Instant.now();
    }

    StockQuote toDomain() {
        return new StockQuote(id, stockCode, baseDate, closePrice, openPrice, highPrice, lowPrice,
                priorDayDiff, fluctuationRate, volume, tradeAmount, listedShares, marketCap,
                source, syncedAt);
    }
}
