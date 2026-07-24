package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataLedgerPeriodRepository extends JpaRepository<LedgerPeriodJpaEntity, Long> {

    Optional<LedgerPeriodJpaEntity> findByPeriodYm(String periodYm);

    boolean existsByPeriodYmAndStatus(String periodYm, String status);
}
