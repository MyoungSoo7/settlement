package github.lms.lemuel.market.application.service;

import github.lms.lemuel.market.application.port.in.GetStockSeriesUseCase;
import github.lms.lemuel.market.application.port.in.GetStocksUseCase;
import github.lms.lemuel.market.application.port.out.LoadStockPort;
import github.lms.lemuel.market.application.port.out.LoadStockQuotePort;
import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockNotFoundException;
import github.lms.lemuel.market.domain.StockQuote;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 종목 시세 공개 조회 서비스.
 *
 * <p>카탈로그 검색, 단일 종목 최신 시세(스냅샷), 시계열을 제공한다. 조회는 캐시
 * ({@code stockCatalog}/{@code stockSnapshots}/{@code stockSeries}) — 수집 배치가 upsert 후
 * 캐시를 evict 해 정합을 유지한다(TTL 만 믿지 않는다).
 */
@Service
@Transactional(readOnly = true)
public class StockQueryService implements GetStocksUseCase, GetStockSeriesUseCase {

    /** 카탈로그 검색 상한 — KRX 상장사 ≈2800 이라 무제한 조회는 막고 페이지네이션 상한을 강제. */
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 100;

    private final LoadStockPort loadStockPort;
    private final LoadStockQuotePort loadStockQuotePort;

    public StockQueryService(LoadStockPort loadStockPort, LoadStockQuotePort loadStockQuotePort) {
        this.loadStockPort = loadStockPort;
        this.loadStockQuotePort = loadStockQuotePort;
    }

    @Override
    @Cacheable(cacheNames = "stockCatalog",
            key = "(#market == null ? 'ALL' : #market) + ':' + (#name == null ? '' : #name) + ':' + #limit")
    public List<Stock> getStocks(Market market, String name, int limit) {
        int resolved = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String normalizedName = (name == null || name.isBlank()) ? null : name.strip();
        return loadStockPort.search(market, normalizedName, resolved);
    }

    @Override
    @Cacheable(cacheNames = "stockSnapshots", key = "#stockCode")
    public StockSnapshot getStock(String stockCode) {
        Stock stock = loadStockPort.findByCode(stockCode)
                .orElseThrow(() -> new StockNotFoundException(stockCode));
        StockQuote latest = loadStockQuotePort.findLatest(stockCode).orElse(null);
        return new StockSnapshot(stock, latest);
    }

    /**
     * 시계열 조회. 순서: 존재검증(404) → 기간검증(400) → 조회.
     *
     * <p>from/to 생략 시 캐시 키가 {@code "code:null:null"} 로 고정되므로, 그날 첫 호출이 잡은
     * {@code [now-1y, now]} 범위가 TTL(600s) 동안 재사용된다(실시간성보다 캐시 히트를 택함).
     */
    @Override
    @Cacheable(cacheNames = "stockSeries", key = "#stockCode + ':' + #from + ':' + #to")
    public List<StockQuote> getSeries(String stockCode, LocalDate from, LocalDate to) {
        if (loadStockPort.findByCode(stockCode).isEmpty()) {
            throw new StockNotFoundException(stockCode);
        }
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusYears(1);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException(
                    "조회 기간이 올바르지 않습니다: from=" + resolvedFrom + ", to=" + resolvedTo);
        }
        return loadStockQuotePort.findSeries(stockCode, resolvedFrom, resolvedTo);
    }
}
