package github.lms.lemuel.common.outbox.adapter.out.persistence;

import github.lms.lemuel.common.outbox.domain.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SpringDataOutboxEventRepository extends JpaRepository<OutboxEventJpaEntity, Long> {

    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, Pageable pageable);

    long countByStatus(OutboxEventStatus status);
}
