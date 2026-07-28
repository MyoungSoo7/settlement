package github.lms.lemuel.recovery.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    /**
     * 정체 스캔 — OPEN 이고 "마지막 활동"(발생 시각 created_at, 상계 이력이 있다면 그중 최신
     * {@code recovery_allocations.created_at})이 cutoff 이전인 채권을 오래된 순으로 잠근다.
     * 동시 상계(offsetForConfirmedSettlement)와의 경합을 락으로 차단해, 스캔 도중 막 상계된
     * 채권을 이관하는 레이스를 막는다.
     *
     * <p>네이티브 쿼리인 이유: {@code cutoff} 는 데이터 표준(N1)에 따라 tz-aware
     * {@link OffsetDateTime} 로 받는데, {@code created_at} 컬럼은 기존 스키마의
     * {@code timestamp}(tz 없음)이다 — JPQL 타입 비교는 이 조합을 거부하지만 Postgres 는
     * {@code timestamp <= timestamptz} 비교를 세션 타임존 기준으로 그대로 지원한다. 락 절도 같은
     * 이유로 JPQL {@code @Lock} 대신 SQL {@code FOR UPDATE} 를 직접 건다.
     */
    @Query(value = "SELECT r.* FROM seller_recoveries r "
            + "WHERE r.status = 'OPEN' "
            + "AND COALESCE((SELECT MAX(a.created_at) FROM recovery_allocations a "
            + "              WHERE a.recovery_id = r.id), r.created_at) <= :cutoff "
            + "ORDER BY r.id ASC LIMIT :limit FOR UPDATE",
            nativeQuery = true)
    List<SellerRecoveryJpaEntity> findStaleOpen(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
