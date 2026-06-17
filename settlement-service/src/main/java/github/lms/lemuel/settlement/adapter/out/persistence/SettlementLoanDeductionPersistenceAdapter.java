package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.RecordLoanDeductionPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class SettlementLoanDeductionPersistenceAdapter implements RecordLoanDeductionPort {

    private final SpringDataSettlementLoanDeductionRepository repository;

    public SettlementLoanDeductionPersistenceAdapter(SpringDataSettlementLoanDeductionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(long settlementId, long sellerId, BigDecimal deducted) {
        // settlement_id 가 할당 PK → save 는 merge(UPSERT) = 멱등.
        repository.save(new SettlementLoanDeductionJpaEntity(settlementId, sellerId, deducted));
    }

    @Override
    public Optional<BigDecimal> findDeduction(long settlementId) {
        return repository.findById(settlementId)
                .map(SettlementLoanDeductionJpaEntity::getDeducted);
    }
}
