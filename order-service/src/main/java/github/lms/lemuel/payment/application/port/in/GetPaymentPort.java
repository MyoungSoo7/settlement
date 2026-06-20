package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;

public interface GetPaymentPort {
    PaymentDomain getPayment(Long paymentId);
}
