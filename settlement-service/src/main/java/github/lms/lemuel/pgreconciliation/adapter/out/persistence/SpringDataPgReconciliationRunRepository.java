package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SpringDataPgReconciliationRunRepository
        extends JpaRepository<PgReconciliationRunJpaEntity, Long> {

    @Query("SELECT r FROM PgReconciliationRunJpaEntity r ORDER BY r.startedAt DESC")
    List<PgReconciliationRunJpaEntity> findRecent(Pageable pageable);
}
