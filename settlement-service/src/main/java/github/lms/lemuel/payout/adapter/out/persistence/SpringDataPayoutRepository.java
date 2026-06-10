package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.domain.PayoutStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataPayoutRepository extends JpaRepository<PayoutJpaEntity, Long> {

    Optional<PayoutJpaEntity> findBySettlementId(Long settlementId);

    List<PayoutJpaEntity> findByStatusOrderByRequestedAtAsc(PayoutStatus status, Pageable pageable);

    /**
     * 원자적 선점: REQUESTED → SENDING. 외부 펌뱅킹 송금 직전에 호출한다.
     *
     * <p>{@code WHERE status = REQUESTED} 조건이 동시성 가드 — 두 배치가 같은 Payout 을 잡으려 해도
     * DB 행 잠금으로 직렬화되어 단 하나의 UPDATE 만 1행을 갱신한다. 진 쪽은 0 행이 반환되어
     * 외부 송금을 시도하지 않는다(이중 송금 방지). 외부 호출 이후가 아니라 *이전* 에 선점이
     * 확정되므로, 낙관적 락이 커밋 시점에야 깨지며 그 사이 이중 송금이 발생하던 윈도우가 사라진다.
     *
     * @return 갱신된 행 수 — 1이면 선점 성공, 0이면 이미 다른 쪽이 가져감
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PayoutJpaEntity p " +
           "SET p.status = github.lms.lemuel.payout.domain.PayoutStatus.SENDING, " +
           "    p.sentAt = :now, p.version = p.version + 1, p.updatedAt = :now " +
           "WHERE p.id = :id AND p.status = github.lms.lemuel.payout.domain.PayoutStatus.REQUESTED")
    int claimForSending(@Param("id") Long id, @Param("now") LocalDateTime now);

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
