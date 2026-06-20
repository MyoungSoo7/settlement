package github.lms.lemuel.common.exception;

public class RefundExceedsPaymentException extends RefundException {
    public RefundExceedsPaymentException(String message) {
        super(message);
    }
}
