package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.FundingViewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface SellerFundingViewRepository extends JpaRepository<SellerFundingViewJpaEntity, Long> {

    @Query("""
            select coalesce(sum(v.amount), 0)
            from SellerFundingViewJpaEntity v
            where v.sellerId = :sellerId and v.status = :status
            """)
    BigDecimal sumBySellerAndStatus(@Param("sellerId") Long sellerId,
                                    @Param("status") FundingViewStatus status);
}
