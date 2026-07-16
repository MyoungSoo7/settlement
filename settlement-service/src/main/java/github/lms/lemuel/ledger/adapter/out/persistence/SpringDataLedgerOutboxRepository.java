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

    /** 상태별 행 수 — FAILED/PENDING 게이지(관측)와 재큐 배치가 사용. */
    long countByStatus(String status);

    /** 상태별 행을 id 오름차순으로(운영자 목록 조회용). */
    List<LedgerOutboxJpaEntity> findByStatusOrderByIdAsc(String status, Pageable pageable);

    /** FAILED 행의 id 를 id 오름차순 최대 limit 건 — 재큐 대상 선별(UPDATE LIMIT 미지원 회피). */
    @Query("SELECT e.id FROM LedgerOutboxJpaEntity e WHERE e.status = 'FAILED' ORDER BY e.id ASC")
    List<Long> findFailedIds(Pageable pageable);

    // 지정 id 들을 FAILED → PENDING 으로 되돌려 재처리 대상으로 만든다. retry_count·last_error·processed_at
    // 을 리셋해 폴러가 새 작업처럼 다시 최대 재시도까지 시도하게 한다. status='FAILED' 조건으로 이미 처리된
    // (DONE) 행이나 진행 중(PENDING) 행을 건드리지 않는다(경합 안전).
    @Modifying
    @Query("UPDATE LedgerOutboxJpaEntity e SET "
            + "e.status = 'PENDING', e.retryCount = 0, e.lastError = null, e.processedAt = null "
            + "WHERE e.id IN :ids AND e.status = 'FAILED'")
    int requeueByIds(@Param("ids") List<Long> ids);

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
