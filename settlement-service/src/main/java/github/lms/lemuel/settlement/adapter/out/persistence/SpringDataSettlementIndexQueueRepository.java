package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSettlementIndexQueueRepository
        extends JpaRepository<SettlementIndexQueueJpaEntity, Long> {
}
