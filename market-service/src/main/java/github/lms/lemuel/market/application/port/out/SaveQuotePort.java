package github.lms.lemuel.market.application.port.out;

import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockQuote;

public interface SaveQuotePort {

    /** 종목 마스터 upsert — 시세 피드에서 파생된 이름/시장 변경을 반영. */
    void upsertStock(Stock stock);

    /** (stockCode, baseDate) UNIQUE upsert — SEED → KRX 대체. */
    void upsertQuote(StockQuote quote);
}
