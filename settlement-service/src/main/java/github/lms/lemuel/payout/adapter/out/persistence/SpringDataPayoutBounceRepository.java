package github.lms.lemuel.payout.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataPayoutBounceRepository extends JpaRepository<PayoutBounceJpaEntity, Long> {

    Optional<PayoutBounceJpaEntity> findByPayoutId(Long payoutId);
}
