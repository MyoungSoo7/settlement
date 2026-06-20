package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.LedgerEntry;

public interface SaveLedgerEntryPort {

    LedgerEntry save(LedgerEntry entry);
}
