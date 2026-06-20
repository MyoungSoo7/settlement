package github.lms.lemuel.common.exception;

public class InvalidPaymentStateException extends RefundException {
    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
