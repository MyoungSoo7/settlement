package github.lms.lemuel.market.application.port.out;

import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;

import java.util.List;
import java.util.Optional;

public interface LoadStockPort {

    /** market=null 이면 전체, name!=null 이면 부분일치. name ASC, limit 상한. */
    List<Stock> search(Market market, String name, int limit);

    Optional<Stock> findByCode(String stockCode);
}
