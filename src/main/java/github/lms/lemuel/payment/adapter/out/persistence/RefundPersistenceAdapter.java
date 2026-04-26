package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RefundPersistenceAdapter implements SaveRefundPort, LoadRefundPort {

    private final SpringDataRefundJpaRepository repository;

    @Override
    public Refund save(Refund refund) {
        RefundJpaEntity saved = repository.save(RefundMapper.toJpa(refund));
        if (refund.getId() == null && saved.getId() != null) {
            refund.assignId(saved.getId());
        }
        return RefundMapper.toDomain(saved);
    }

    @Override
    public Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey) {
        return repository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                .map(RefundMapper::toDomain);
    }
}
