package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.SettlementViewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SellerSettlementViewRepository extends JpaRepository<SellerSettlementViewJpaEntity, Long> {

    @Query("""
            select coalesce(sum(v.amount), 0)
            from SellerSettlementViewJpaEntity v
            where v.sellerId = :sellerId and v.status = :status
            """)
    BigDecimal sumBySellerAndStatus(@Param("sellerId") Long sellerId,
                                    @Param("status") SettlementViewStatus status);

    /** 대출 실행 직전 재검증용 — 셀러의 미지급 투영 행을 비관적 락으로 잡고 합계는 호출측에서 계산. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from SellerSettlementViewJpaEntity v
            where v.sellerId = :sellerId and v.status = :status
            """)
    List<SellerSettlementViewJpaEntity> findBySellerAndStatusForUpdate(@Param("sellerId") Long sellerId,
                                                                       @Param("status") SettlementViewStatus status);
}
