package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.payment.domain.PaymentStatus;

public class InvalidPaymentStateException extends RuntimeException {
    public InvalidPaymentStateException(PaymentStatus currentStatus, PaymentStatus requiredStatus) {
        super(String.format("Invalid payment state. Current: %s, Required: %s", currentStatus, requiredStatus));
    }

    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
