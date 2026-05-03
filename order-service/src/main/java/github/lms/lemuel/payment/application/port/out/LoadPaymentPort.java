package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.PaymentDomain;

import java.util.Optional;

public interface LoadPaymentPort {
    Optional<PaymentDomain> loadById(Long id);
    Optional<PaymentDomain> loadByOrderId(Long orderId);
}
