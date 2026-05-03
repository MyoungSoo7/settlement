package github.lms.lemuel.common.exception;

public class RefundException extends RuntimeException {
    public RefundException(String message) {
        super(message);
    }

    public RefundException(String message, Throwable cause) {
        super(message, cause);
    }
}
