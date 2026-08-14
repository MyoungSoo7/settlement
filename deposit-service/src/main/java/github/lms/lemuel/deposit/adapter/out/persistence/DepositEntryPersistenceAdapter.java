package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.application.port.out.SaveDepositEntryPort;
import github.lms.lemuel.deposit.domain.DepositEntry;
import org.springframework.stereotype.Component;

@Component
public class DepositEntryPersistenceAdapter implements SaveDepositEntryPort {

    private final SpringDataDepositEntryRepository repo;

    public DepositEntryPersistenceAdapter(SpringDataDepositEntryRepository repo) {
        this.repo = repo;
    }

    @Override
    public DepositEntry save(DepositEntry entry) {
        DepositEntryJpaEntity entity = new DepositEntryJpaEntity(
                entry.getAccountId(), entry.getEntryType(), entry.getAmount(),
                entry.getReferenceId(), entry.getReferenceType(),
                entry.getOffsetSequence(), entry.getSourceHoldId());
        DepositEntryJpaEntity saved = repo.save(entity);
        if (entry.getId() == null) {
            entry.assignId(saved.getId());
        }
        return entry;
    }
}
