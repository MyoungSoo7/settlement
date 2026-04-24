package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SpringDataJournalEntryJpaRepository extends JpaRepository<JournalEntryJpaEntity, Long> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    List<JournalEntryJpaEntity> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
}
