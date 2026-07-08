package github.lms.lemuel.market.application.port.in;

import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockQuote;

import java.util.List;

public interface GetStocksUseCase {

    /** 종목 카탈로그 조회 — market=null 이면 전체, name 부분일치(null 이면 무시), limit 상한. */
    List<Stock> getStocks(Market market, String name, int limit);

    /** 단일 종목 + 최신 시세(관측치 부족 시 quote=null). */
    StockSnapshot getStock(String stockCode);

    record StockSnapshot(Stock stock, StockQuote latest) { }
}
