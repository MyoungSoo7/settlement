package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.domain.PayoutStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataPayoutRepository extends JpaRepository<PayoutJpaEntity, Long> {

    Optional<PayoutJpaEntity> findBySettlementId(Long settlementId);

    List<PayoutJpaEntity> findByStatusOrderByRequestedAtAsc(PayoutStatus status, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PayoutJpaEntity p " +
           "WHERE p.sellerId = :sellerId AND p.status = 'COMPLETED' " +
           "AND p.completedAt >= :from AND p.completedAt < :to")
    BigDecimal sumCompletedBySellerBetween(@Param("sellerId") Long sellerId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PayoutJpaEntity p " +
           "WHERE p.status = 'COMPLETED' " +
           "AND p.completedAt >= :from AND p.completedAt < :to")
    BigDecimal sumCompletedBetween(@Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);
}
