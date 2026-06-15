package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.RecordRepaymentPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class RepaymentPersistenceAdapter implements RecordRepaymentPort {

    private final LoanRepaymentRepository repository;

    public RepaymentPersistenceAdapter(LoanRepaymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsForSettlement(long settlementId) {
        return repository.existsBySettlementId(settlementId);
    }

    @Override
    public void record(long settlementId, long sellerId, BigDecimal deducted) {
        repository.save(new LoanRepaymentJpaEntity(settlementId, sellerId, deducted));
    }
}
