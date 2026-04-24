package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.JournalEntry;
import java.util.List;

public interface LoadJournalEntryPort {
    List<JournalEntry> findByReference(String referenceType, Long referenceId);
}
