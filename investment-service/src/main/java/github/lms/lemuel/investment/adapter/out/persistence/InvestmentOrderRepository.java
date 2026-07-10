package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrderJpaEntity, Long> {

    List<InvestmentOrderJpaEntity> findBySellerIdOrderByIdAsc(Long sellerId);

    @Query("""
            select coalesce(sum(o.amount), 0)
            from InvestmentOrderJpaEntity o
            where o.sellerId = :sellerId and o.status = :status
            """)
    BigDecimal sumBySellerAndStatus(@Param("sellerId") Long sellerId,
                                    @Param("status") InvestmentOrderStatus status);
}
