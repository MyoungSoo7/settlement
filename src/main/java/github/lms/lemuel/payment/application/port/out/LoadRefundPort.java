package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.Refund;

import java.util.Optional;

public interface LoadRefundPort {
    Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);
}
