package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.FundingViewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SellerFundingViewRepository extends JpaRepository<SellerFundingViewJpaEntity, Long> {

    @Query("""
            select coalesce(sum(v.amount), 0)
            from SellerFundingViewJpaEntity v
            where v.sellerId = :sellerId and v.status = :status
            """)
    BigDecimal sumBySellerAndStatus(@Param("sellerId") Long sellerId,
                                    @Param("status") FundingViewStatus status);

    /**
     * 재원 재검증 전용 — 셀러의 재원 행을 행 비관적 락(SELECT ... FOR UPDATE)으로 조회한다.
     * 합계는 aggregate 쿼리에 FOR UPDATE 를 걸 수 없어 호출측(adapter)에서 계산한다
     * (loan {@code findBySellerAndStatusForUpdate} 동형). 같은 셀러 동시 집행을 첫 커밋까지 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from SellerFundingViewJpaEntity v
            where v.sellerId = :sellerId and v.status = :status
            """)
    List<SellerFundingViewJpaEntity> findBySellerAndStatusForUpdate(@Param("sellerId") Long sellerId,
                                                                    @Param("status") FundingViewStatus status);
}
