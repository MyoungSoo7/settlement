package github.lms.lemuel.recovery.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataRecoveryAllocationJpaRepository
        extends JpaRepository<RecoveryAllocationJpaEntity, Long> {

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM RecoveryAllocationJpaEntity a "
            + "WHERE a.settlementId = :settlementId")
    java.math.BigDecimal sumBySettlementId(@Param("settlementId") Long settlementId);

    /** 셀러의 상계 이력 — 채권 join 으로 셀러 축 필터 (최신순). */
    @Query("SELECT a FROM RecoveryAllocationJpaEntity a, SellerRecoveryJpaEntity r "
            + "WHERE a.recoveryId = r.id AND r.sellerId = :sellerId ORDER BY a.id DESC")
    List<RecoveryAllocationJpaEntity> findBySellerId(@Param("sellerId") Long sellerId);
}
