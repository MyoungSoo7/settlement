package github.lms.lemuel.ledger.domain.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String code) {
        super("Account not found: " + code);
    }
}
