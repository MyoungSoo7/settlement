package github.lms.lemuel.payout.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataPayoutJpaRepository extends JpaRepository<PayoutJpaEntity, Long> {
    Optional<PayoutJpaEntity> findBySettlementId(Long settlementId);
}
