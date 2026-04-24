package github.lms.lemuel.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SpringDataAccountBalanceSnapshotJpaRepository
        extends JpaRepository<AccountBalanceSnapshotJpaEntity, Long> {

    Optional<AccountBalanceSnapshotJpaEntity> findTopByAccountIdOrderBySnapshotAtDesc(Long accountId);
}
