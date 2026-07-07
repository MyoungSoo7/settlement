package github.lms.lemuel.market.adapter.out.persistence;

import github.lms.lemuel.market.domain.Market;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockRepository extends JpaRepository<StockJpaEntity, String> {

    List<StockJpaEntity> findByOrderByNameAsc(Limit limit);

    List<StockJpaEntity> findByMarketOrderByNameAsc(Market market, Limit limit);

    List<StockJpaEntity> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Limit limit);

    List<StockJpaEntity> findByMarketAndNameContainingIgnoreCaseOrderByNameAsc(
            Market market, String name, Limit limit);
}
