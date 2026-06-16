package github.lms.lemuel.loan.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepaymentRepository extends JpaRepository<LoanRepaymentJpaEntity, Long> {
    boolean existsBySettlementId(Long settlementId);
}
