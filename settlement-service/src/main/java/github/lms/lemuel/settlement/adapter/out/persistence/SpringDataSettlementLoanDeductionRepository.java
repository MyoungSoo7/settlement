package github.lms.lemuel.settlement.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSettlementLoanDeductionRepository
        extends JpaRepository<SettlementLoanDeductionJpaEntity, Long> {
}
