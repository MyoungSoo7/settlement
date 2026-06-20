package github.lms.lemuel.ledger.domain.exception;

public class LedgerNotFoundException extends RuntimeException {

    public LedgerNotFoundException(Long id) {
        super("LedgerEntry not found: id=" + id);
    }

    public LedgerNotFoundException(String message) {
        super(message);
    }
}
