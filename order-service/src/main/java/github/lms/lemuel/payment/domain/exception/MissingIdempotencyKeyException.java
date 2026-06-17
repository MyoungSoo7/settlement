package github.lms.lemuel.payment.domain.exception;

public class MissingIdempotencyKeyException extends RefundException {
    public MissingIdempotencyKeyException(String message) {
        super(message);
    }
}
