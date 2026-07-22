package github.lms.lemuel.recovery.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataSellerRecoveryJpaRepository extends JpaRepository<SellerRecoveryJpaEntity, Long> {

    Optional<SellerRecoveryJpaEntity> findBySourceAdjustmentId(Long sourceAdjustmentId);

    /** 상계 스캔 — 오래된 채권부터 잠그고 소진한다 (동시 확정 청크의 이중 상계 차단). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SellerRecoveryJpaEntity r "
            + "WHERE r.sellerId = :sellerId AND r.status = 'OPEN' ORDER BY r.id ASC")
    List<SellerRecoveryJpaEntity> findOpenBySellerIdForUpdate(@Param("sellerId") Long sellerId);

    List<SellerRecoveryJpaEntity> findBySellerIdOrderByIdDesc(Long sellerId);
}
