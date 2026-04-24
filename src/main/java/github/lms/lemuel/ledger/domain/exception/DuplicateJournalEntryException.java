package github.lms.lemuel.ledger.domain.exception;

public class DuplicateJournalEntryException extends RuntimeException {
    public DuplicateJournalEntryException(String idempotencyKey) {
        super("Journal entry already exists with idempotency key: " + idempotencyKey);
    }
}
