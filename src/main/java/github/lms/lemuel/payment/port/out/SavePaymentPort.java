package github.lms.lemuel.payment.port.out;

import github.lms.lemuel.payment.domain.Payment;

public interface SavePaymentPort {
    Payment save(Payment payment);
}
