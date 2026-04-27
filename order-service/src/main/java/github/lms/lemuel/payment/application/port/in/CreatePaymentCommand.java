package github.lms.lemuel.payment.application.port.in;

/**
 * Command object for creating a new payment
 */
public class CreatePaymentCommand {
    private final Long orderId;
    private final String paymentMethod;

    public CreatePaymentCommand(Long orderId, String paymentMethod) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("paymentMethod must not be blank");
        }
        this.orderId = orderId;
        this.paymentMethod = paymentMethod;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }
}
