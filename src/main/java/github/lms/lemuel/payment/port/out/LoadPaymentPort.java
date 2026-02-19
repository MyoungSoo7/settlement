package github.lms.lemuel.payment.port.out;

import github.lms.lemuel.payment.domain.Payment;

import java.util.Optional;

public interface LoadPaymentPort {
    Optional<Payment> loadById(Long id);
    Optional<Payment> loadByOrderId(Long orderId);
}
