package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.JournalEntry;

public interface SaveJournalEntryPort {
    JournalEntry save(JournalEntry journalEntry);
    boolean existsByIdempotencyKey(String idempotencyKey);
}
