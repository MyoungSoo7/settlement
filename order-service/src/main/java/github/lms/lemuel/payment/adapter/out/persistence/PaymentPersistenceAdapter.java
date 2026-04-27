package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapter implementing outbound persistence ports
 */
@Component
public class PaymentPersistenceAdapter implements LoadPaymentPort, SavePaymentPort {

    private final PaymentJpaRepository paymentJpaRepository;
    private final PaymentMapper paymentMapper;

    public PaymentPersistenceAdapter(PaymentJpaRepository paymentJpaRepository, PaymentMapper paymentMapper) {
        this.paymentJpaRepository = paymentJpaRepository;
        this.paymentMapper = paymentMapper;
    }

    @Override
    public Optional<PaymentDomain> loadById(Long id) {
        return paymentJpaRepository.findById(id)
            .map(paymentMapper::toDomain);
    }

    @Override
    public Optional<PaymentDomain> loadByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId)
            .map(paymentMapper::toDomain);
    }

    @Override
    public PaymentDomain save(PaymentDomain paymentDomain) {
        PaymentJpaEntity entity = paymentMapper.toJpaEntity(paymentDomain);
        PaymentJpaEntity savedEntity = paymentJpaRepository.save(entity);
        return paymentMapper.toDomain(savedEntity);
    }
}
