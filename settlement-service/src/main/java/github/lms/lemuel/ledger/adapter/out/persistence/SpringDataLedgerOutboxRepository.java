package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataLedgerOutboxRepository extends JpaRepository<LedgerOutboxJpaEntity, Long> {

    @Query("SELECT e FROM LedgerOutboxJpaEntity e WHERE e.status = 'PENDING' ORDER BY e.id ASC")
    List<LedgerOutboxJpaEntity> findPending(Pageable pageable);

    @Modifying
    @Query("UPDATE LedgerOutboxJpaEntity e "
            + "SET e.status = 'DONE', e.processedAt = CURRENT_TIMESTAMP "
            + "WHERE e.id = :id")
    void markDone(@Param("id") Long id);

    // retry_count 증가 후 한도 도달이면 FAILED + processed_at 기록, 아니면 PENDING 유지(재시도).
    @Modifying
    @Query("UPDATE LedgerOutboxJpaEntity e SET "
            + "e.retryCount = e.retryCount + 1, "
            + "e.lastError = :error, "
            + "e.status = CASE WHEN e.retryCount + 1 >= :maxRetry THEN 'FAILED' ELSE 'PENDING' END, "
            + "e.processedAt = CASE WHEN e.retryCount + 1 >= :maxRetry THEN CURRENT_TIMESTAMP ELSE e.processedAt END "
            + "WHERE e.id = :id")
    void markFailed(@Param("id") Long id, @Param("error") String error, @Param("maxRetry") int maxRetry);
}
