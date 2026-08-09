package github.lms.lemuel.deposit.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDepositEntryRepository extends JpaRepository<DepositEntryJpaEntity, Long> {
}
