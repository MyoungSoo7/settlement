package github.lms.lemuel.payment.port.in;

import github.lms.lemuel.payment.domain.Payment;

public interface CreatePaymentPort {
    Payment createPayment(CreatePaymentCommand command);
}
