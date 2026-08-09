package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositEntry;

public interface SaveDepositEntryPort {
    DepositEntry save(DepositEntry entry);
}
