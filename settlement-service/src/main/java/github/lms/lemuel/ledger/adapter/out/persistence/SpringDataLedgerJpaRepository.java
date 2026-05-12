package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataLedgerJpaRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    boolean existsByReferenceIdAndReferenceType(Long referenceId, String referenceType);

    List<LedgerEntryJpaEntity> findByReferenceIdAndReferenceType(Long referenceId, String referenceType);

    List<LedgerEntryJpaEntity> findBySettlementDateBetween(LocalDate from, LocalDate to);
}
