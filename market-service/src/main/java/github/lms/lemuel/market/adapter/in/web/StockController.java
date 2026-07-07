package github.lms.lemuel.market.adapter.in.web;

import github.lms.lemuel.market.application.port.in.GetStockSeriesUseCase;
import github.lms.lemuel.market.application.port.in.GetStocksUseCase;
import github.lms.lemuel.market.application.port.in.GetStocksUseCase.StockSnapshot;
import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockQuote;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

/**
 * 종목 시세 공개 조회 API — 카탈로그 검색, 단건 최신 시세, 시계열.
 *
 * <p>전부 공개 시장 데이터라 무인증(GET). 응답 DTO 는 도메인 노출을 막는 컨트롤러 내부 record.
 * PER/PBR 등 밸류에이션은 여기서 계산하지 않는다 — financial-service 의 공개 GET 과 소비측에서 조인.
 */
@RestController
@RequestMapping("/api/market/stocks")
public class StockController {

    private final GetStocksUseCase getStocksUseCase;
    private final GetStockSeriesUseCase getStockSeriesUseCase;

    public StockController(GetStocksUseCase getStocksUseCase,
                           GetStockSeriesUseCase getStockSeriesUseCase) {
        this.getStocksUseCase = getStocksUseCase;
        this.getStockSeriesUseCase = getStockSeriesUseCase;
    }

    @GetMapping
    public List<StockResponse> stocks(
            @RequestParam(required = false) Market market,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "100") int limit) {
        return getStocksUseCase.getStocks(market, name, limit).stream()
                .map(StockResponse::from)
                .toList();
    }

    @GetMapping("/{stockCode}/latest")
    public StockSnapshotResponse latest(@PathVariable String stockCode) {
        return StockSnapshotResponse.from(getStocksUseCase.getStock(stockCode));
    }

    @GetMapping("/{stockCode}/series")
    public SeriesResponse series(
            @PathVariable String stockCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 존재검증 겸 메타데이터(name/market) — 없는 code 는 여기서 404 (기간검증보다 우선).
        Stock stock = getStocksUseCase.getStock(stockCode).stock();
        List<StockQuote> points = getStockSeriesUseCase.getSeries(stockCode, from, to);
        return SeriesResponse.from(stock, points);
    }

    // ----- 응답 DTO (컨트롤러 내부 record) -----

    record StockResponse(String stockCode, String name, String market) {
        static StockResponse from(Stock stock) {
            return new StockResponse(stock.stockCode(), stock.name(), stock.market().name());
        }
    }

    record StockSnapshotResponse(String stockCode, String name, String market, QuoteResponse latest) {
        static StockSnapshotResponse from(StockSnapshot snapshot) {
            Stock stock = snapshot.stock();
            QuoteResponse latest = snapshot.latest() == null ? null : QuoteResponse.from(snapshot.latest());
            return new StockSnapshotResponse(stock.stockCode(), stock.name(), stock.market().name(), latest);
        }
    }

    record QuoteResponse(LocalDate baseDate, BigDecimal closePrice, BigDecimal openPrice,
                         BigDecimal highPrice, BigDecimal lowPrice, BigDecimal priorDayDiff,
                         BigDecimal fluctuationRate, BigInteger volume, BigInteger tradeAmount,
                         BigInteger listedShares, BigInteger marketCap, String source) {
        static QuoteResponse from(StockQuote q) {
            return new QuoteResponse(q.baseDate(), q.closePrice(), q.openPrice(), q.highPrice(),
                    q.lowPrice(), q.priorDayDiff(), q.fluctuationRate(), q.volume(), q.tradeAmount(),
                    q.listedShares(), q.marketCap(), q.source().name());
        }
    }

    record SeriesResponse(String stockCode, String name, String market, List<SeriesPoint> points) {
        static SeriesResponse from(Stock stock, List<StockQuote> values) {
            List<SeriesPoint> points = values.stream()
                    .map(v -> new SeriesPoint(v.baseDate(), v.closePrice(), v.volume(),
                            v.marketCap(), v.source().name()))
                    .toList();
            return new SeriesResponse(stock.stockCode(), stock.name(), stock.market().name(), points);
        }
    }

    record SeriesPoint(LocalDate baseDate, BigDecimal closePrice, BigInteger volume,
                       BigInteger marketCap, String source) { }
}
