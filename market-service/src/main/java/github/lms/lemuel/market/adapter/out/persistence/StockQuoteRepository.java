package github.lms.lemuel.market.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockQuoteRepository extends JpaRepository<StockQuoteJpaEntity, Long> {

    Optional<StockQuoteJpaEntity> findFirstByStockCodeOrderByBaseDateDesc(String stockCode);

    List<StockQuoteJpaEntity> findByStockCodeAndBaseDateBetweenOrderByBaseDateAsc(
            String stockCode, LocalDate from, LocalDate to);

    Optional<StockQuoteJpaEntity> findByStockCodeAndBaseDate(String stockCode, LocalDate baseDate);
}
