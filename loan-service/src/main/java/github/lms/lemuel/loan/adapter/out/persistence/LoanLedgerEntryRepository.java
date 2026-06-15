package github.lms.lemuel.loan.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanLedgerEntryRepository extends JpaRepository<LoanLedgerEntryJpaEntity, Long> {
}
