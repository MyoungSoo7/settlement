package github.lms.lemuel.market.application.port.in;

import github.lms.lemuel.market.domain.StockQuote;

import java.time.LocalDate;
import java.util.List;

public interface GetStockSeriesUseCase {

    /** from/to null 이면 최근 1년. baseDate ASC. */
    List<StockQuote> getSeries(String stockCode, LocalDate from, LocalDate to);
}
