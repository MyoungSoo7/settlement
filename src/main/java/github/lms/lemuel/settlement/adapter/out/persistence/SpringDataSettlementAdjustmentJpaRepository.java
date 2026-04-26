package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSettlementAdjustmentJpaRepository
        extends JpaRepository<SettlementAdjustmentJpaEntity, Long> {
    boolean existsByRefundId(Long refundId);
}
