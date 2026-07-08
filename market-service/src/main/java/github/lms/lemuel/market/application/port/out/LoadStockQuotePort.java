package github.lms.lemuel.market.application.port.out;

import github.lms.lemuel.market.domain.StockQuote;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoadStockQuotePort {

    /** 최신 시세 1건. */
    Optional<StockQuote> findLatest(String stockCode);

    /** [from, to] 시세 시계열, baseDate ASC. */
    List<StockQuote> findSeries(String stockCode, LocalDate from, LocalDate to);
}
