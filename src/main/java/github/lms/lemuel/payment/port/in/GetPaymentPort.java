package github.lms.lemuel.payment.port.in;

import github.lms.lemuel.payment.domain.Payment;

public interface GetPaymentPort {
    Payment getPayment(Long paymentId);
}
