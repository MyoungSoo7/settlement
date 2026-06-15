package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import org.springframework.stereotype.Component;

@Component
public class LedgerPersistenceAdapter implements AppendLedgerPort {

    private final LoanLedgerEntryRepository repository;

    public LedgerPersistenceAdapter(LoanLedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(LoanLedgerEntry entry) {
        repository.save(new LoanLedgerEntryJpaEntity(
                entry.getDebit(),
                entry.getCredit(),
                entry.getAmount(),
                entry.getRefType(),
                entry.getRefId()));
    }
}
