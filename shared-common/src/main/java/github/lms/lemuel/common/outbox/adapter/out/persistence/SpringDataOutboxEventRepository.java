package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataOutboxEventRepository extends JpaRepository<OutboxEventJpaEntity, Long> {

    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(@Param("status") OutboxEventStatus status, Pageable pageable);

    long countByStatus(OutboxEventStatus status);

    Optional<OutboxEventJpaEntity> findByEventId(UUID eventId);

    /**
     * claim 후보(PENDING + 미클레임/리스만료) id 를 created_at 순으로 잠그며 선택한다.
     * {@code FOR UPDATE SKIP LOCKED} 로 다른 워커가 잠근 행은 건너뛰어, 동시 폴링 시 disjoint 분할.
     * 반드시 {@link #stampClaim}/발행과 같은 트랜잭션 안에서 호출해야 잠금이 유지된다.
     */
    @Query(value = """
            SELECT e.id FROM #{@outboxSchema.name}.outbox_events e
            WHERE e.status = 'PENDING'
              AND (e.claimed_at IS NULL OR e.claimed_at < now() - (:leaseSeconds * INTERVAL '1 second'))
            ORDER BY e.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> selectClaimableIds(@Param("limit") int limit, @Param("leaseSeconds") long leaseSeconds);

    @Modifying
    @Query(value = "UPDATE #{@outboxSchema.name}.outbox_events SET claimed_at = :now, claimed_by = :worker WHERE id IN (:ids)",
            nativeQuery = true)
    void stampClaim(@Param("ids") List<Long> ids, @Param("worker") String worker, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = "UPDATE #{@outboxSchema.name}.outbox_events SET claimed_at = NULL, claimed_by = NULL WHERE id IN (:ids)",
            nativeQuery = true)
    void clearClaim(@Param("ids") List<Long> ids);

    List<OutboxEventJpaEntity> findByIdInOrderByCreatedAtAsc(List<Long> ids);
}
