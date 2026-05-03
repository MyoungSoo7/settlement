package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataPgReconciliationDiscrepancyRepository
        extends JpaRepository<PgReconciliationDiscrepancyJpaEntity, Long> {

    List<PgReconciliationDiscrepancyJpaEntity> findByRunIdOrderByCreatedAtAsc(Long runId);
}
