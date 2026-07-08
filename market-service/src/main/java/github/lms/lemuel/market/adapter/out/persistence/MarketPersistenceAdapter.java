package github.lms.lemuel.market.adapter.out.persistence;

import github.lms.lemuel.market.application.port.out.LoadStockPort;
import github.lms.lemuel.market.application.port.out.LoadStockQuotePort;
import github.lms.lemuel.market.application.port.out.SaveQuotePort;
import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockQuote;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class MarketPersistenceAdapter
        implements LoadStockPort, LoadStockQuotePort, SaveQuotePort {

    private final StockRepository stockRepository;
    private final StockQuoteRepository stockQuoteRepository;

    public MarketPersistenceAdapter(StockRepository stockRepository,
                                    StockQuoteRepository stockQuoteRepository) {
        this.stockRepository = stockRepository;
        this.stockQuoteRepository = stockQuoteRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stock> search(Market market, String name, int limit) {
        Limit cap = Limit.of(limit);
        List<StockJpaEntity> rows;
        if (market != null && name != null) {
            rows = stockRepository.findByMarketAndNameContainingIgnoreCaseOrderByNameAsc(market, name, cap);
        } else if (market != null) {
            rows = stockRepository.findByMarketOrderByNameAsc(market, cap);
        } else if (name != null) {
            rows = stockRepository.findByNameContainingIgnoreCaseOrderByNameAsc(name, cap);
        } else {
            rows = stockRepository.findByOrderByNameAsc(cap);
        }
        return rows.stream().map(StockJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Stock> findByCode(String stockCode) {
        return stockRepository.findById(stockCode).map(StockJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockQuote> findLatest(String stockCode) {
        return stockQuoteRepository.findFirstByStockCodeOrderByBaseDateDesc(stockCode)
                .map(StockQuoteJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockQuote> findSeries(String stockCode, LocalDate from, LocalDate to) {
        return stockQuoteRepository
                .findByStockCodeAndBaseDateBetweenOrderByBaseDateAsc(stockCode, from, to).stream()
                .map(StockQuoteJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void upsertStock(Stock stock) {
        StockJpaEntity entity = stockRepository.findById(stock.stockCode())
                .map(existing -> {
                    existing.applyDomain(stock);
                    return existing;
                })
                .orElseGet(() -> StockJpaEntity.fromDomain(stock));
        stockRepository.save(entity);
    }

    @Override
    @Transactional
    public void upsertQuote(StockQuote quote) {
        StockQuoteJpaEntity entity = stockQuoteRepository
                .findByStockCodeAndBaseDate(quote.stockCode(), quote.baseDate())
                .map(existing -> {
                    existing.applyDomain(quote);
                    return existing;
                })
                .orElseGet(() -> StockQuoteJpaEntity.fromDomain(quote));
        stockQuoteRepository.save(entity);
    }
}
