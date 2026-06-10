package github.lms.lemuel.settlement.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for Settlement
 */
public interface SpringDataSettlementJpaRepository extends JpaRepository<SettlementJpaEntity, Long> {

    Optional<SettlementJpaEntity> findByPaymentId(Long paymentId);

    /**
     * 환불 정산 차감용 비관적 락 조회 (SELECT ... FOR UPDATE).
     * 동시 환불이 같은 정산 행을 읽고 각자 차감해 lost update 가 나는 것을 막기 위해
     * 트랜잭션이 끝날 때까지 행을 잠근다. 반드시 @Transactional 컨텍스트 안에서 호출할 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SettlementJpaEntity s WHERE s.paymentId = :paymentId")
    Optional<SettlementJpaEntity> findByPaymentIdForUpdate(@Param("paymentId") Long paymentId);

    List<SettlementJpaEntity> findBySettlementDate(LocalDate settlementDate);

    List<SettlementJpaEntity> findBySettlementDateAndStatus(LocalDate settlementDate, String status);

    /**
     * 정산 확정 배치용 비관적 락 조회 — 해당 일자의 특정 상태(REQUESTED) 정산 행을 잠근다.
     *
     * <p>스케줄 배치(ShedLock)와 운영자 수동 트리거가 같은 일자를 동시에 확정하려 해도, 두 번째
     * 트랜잭션은 이 락에서 대기하다 첫 번째 커밋 후 진행한다. 그 시점엔 행이 이미 DONE 이라
     * 결과 집합에서 빠져 이중 확정/이중 원장 적재가 발생하지 않는다.
     * 데드락 회피를 위해 id 오름차순으로 결정적 잠금. 반드시 @Transactional 안에서 호출.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SettlementJpaEntity s " +
           "WHERE s.settlementDate = :settlementDate AND s.status = :status " +
           "ORDER BY s.id ASC")
    List<SettlementJpaEntity> findBySettlementDateAndStatusForUpdate(
            @Param("settlementDate") LocalDate settlementDate,
            @Param("status") String status);

    /**
     * 보류 해제 배치 — release_date <= today 이고 아직 released=false 이며 holdback > 0 인 row.
     *
     * 비관적 락(SELECT ... FOR UPDATE)으로 잠가, 동시 환불(consumeHoldbackForRefund)이 같은 정산의
     * holdback 을 동시에 건드려 lost update 나는 것을 막는다. 환불 차감 경로와 동일한 락 전략.
     * 데드락 회피를 위해 id 까지 포함한 결정적 순서로 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT s FROM SettlementJpaEntity s " +
            "WHERE s.holdbackReleased = false " +
            "  AND s.holdbackAmount > 0 " +
            "  AND s.holdbackReleaseDate <= :today " +
            "ORDER BY s.holdbackReleaseDate ASC, s.id ASC")
    List<SettlementJpaEntity> findReleasableHoldbacks(
            @Param("today") LocalDate today,
            org.springframework.data.domain.Pageable pageable);
}
