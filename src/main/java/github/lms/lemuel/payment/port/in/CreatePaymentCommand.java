package github.lms.lemuel.payment.port.in;

public class CreatePaymentCommand {
    private final Long orderId;
    private final String paymentMethod;

    public CreatePaymentCommand(Long orderId, String paymentMethod) {
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
