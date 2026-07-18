package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationRunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SpringDataPgReconciliationRunRepository
        extends JpaRepository<PgReconciliationRunJpaEntity, Long> {

    @Query("SELECT r FROM PgReconciliationRunJpaEntity r ORDER BY r.startedAt DESC")
    List<PgReconciliationRunJpaEntity> findRecent(Pageable pageable);

    /** 같은 파일(SHA-256) 로 이미 완료된 run — 재업로드 멱등 판정. FAILED 는 재시도 허용 위해 제외. */
    Optional<PgReconciliationRunJpaEntity> findFirstByFileSha256AndStatusOrderByIdDesc(
            String fileSha256, ReconciliationRunStatus status);
}
