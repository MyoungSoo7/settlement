package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RefundPersistenceAdapter implements LoadRefundPort, SaveRefundPort {

    private final SpringDataRefundJpaRepository repository;

    public RefundPersistenceAdapter(SpringDataRefundJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey) {
        return repository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                .map(this::toDomain);
    }

    @Override
    public List<Refund> findAllByPaymentId(Long paymentId) {
        return repository.findByPaymentIdOrderByRequestedAtDesc(paymentId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Refund save(Refund refund) {
        RefundJpaEntity entity = toEntity(refund);
        RefundJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    private RefundJpaEntity toEntity(Refund domain) {
        RefundJpaEntity entity = new RefundJpaEntity();
        entity.setId(domain.getId());
        entity.setPaymentId(domain.getPaymentId());
        entity.setAmount(domain.getAmount());
        entity.setStatus(domain.getStatus().name());
        entity.setReason(domain.getReason());
        entity.setIdempotencyKey(domain.getIdempotencyKey());
        entity.setRequestedAt(domain.getRequestedAt());
        entity.setCompletedAt(domain.getCompletedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private Refund toDomain(RefundJpaEntity entity) {
        Refund domain = new Refund();
        domain.setId(entity.getId());
        domain.setPaymentId(entity.getPaymentId());
        domain.setAmount(entity.getAmount());
        domain.setStatus(Refund.Status.valueOf(entity.getStatus()));
        domain.setReason(entity.getReason());
        domain.setIdempotencyKey(entity.getIdempotencyKey());
        domain.setRequestedAt(entity.getRequestedAt());
        domain.setCompletedAt(entity.getCompletedAt());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }
}
