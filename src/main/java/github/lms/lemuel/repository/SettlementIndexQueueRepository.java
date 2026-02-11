package github.lms.lemuel.repository;

import github.lms.lemuel.domain.SettlementIndexQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SettlementIndexQueueRepository extends JpaRepository<SettlementIndexQueue, Long> {

    List<SettlementIndexQueue> findByStatus(String status);

    @Query("SELECT q FROM SettlementIndexQueue q WHERE q.status = 'PENDING' " +
           "AND (q.nextRetryAt IS NULL OR q.nextRetryAt <= :now) " +
           "ORDER BY q.createdAt ASC")
    List<SettlementIndexQueue> findPendingItems(LocalDateTime now);

    @Query("SELECT q FROM SettlementIndexQueue q WHERE q.status = 'FAILED' " +
           "AND q.retryCount < q.maxRetries " +
           "AND q.nextRetryAt <= :now")
    List<SettlementIndexQueue> findRetryableFailedItems(LocalDateTime now);
}
