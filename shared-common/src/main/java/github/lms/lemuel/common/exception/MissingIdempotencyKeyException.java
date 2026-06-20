package github.lms.lemuel.common.exception;

public class MissingIdempotencyKeyException extends RefundException {
    public MissingIdempotencyKeyException(String message) {
        super(message);
    }
}
