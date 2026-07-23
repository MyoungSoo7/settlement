package github.lms.lemuel.payout.adapter.out.persistence;

import github.lms.lemuel.payout.application.port.out.LoadPayoutBouncePort;
import github.lms.lemuel.payout.application.port.out.SavePayoutBouncePort;
import github.lms.lemuel.payout.domain.PayoutBounce;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PayoutBouncePersistenceAdapter implements LoadPayoutBouncePort, SavePayoutBouncePort {

    private final SpringDataPayoutBounceRepository repository;

    public PayoutBouncePersistenceAdapter(SpringDataPayoutBounceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PayoutBounce> findByPayoutId(Long payoutId) {
        return repository.findByPayoutId(payoutId).map(PayoutBouncePersistenceAdapter::toDomain);
    }

    @Override
    public PayoutBounce save(PayoutBounce bounce) {
        PayoutBounceJpaEntity entity;
        if (bounce.getId() == null) {
            entity = new PayoutBounceJpaEntity(null, bounce.getPayoutId(), bounce.getReason(),
                    bounce.getResolvedPayoutId(), bounce.getOperatorId(),
                    bounce.getBouncedAt(), bounce.getCreatedAt());
        } else {
            entity = repository.findById(bounce.getId())
                    .orElseThrow(() -> new IllegalStateException("PayoutBounce not found: " + bounce.getId()));
            entity.applyResolved(bounce.getResolvedPayoutId());
        }
        return toDomain(repository.save(entity));
    }

    private static PayoutBounce toDomain(PayoutBounceJpaEntity e) {
        return PayoutBounce.rehydrate(e.getId(), e.getPayoutId(), e.getReason(), e.getResolvedPayoutId(),
                e.getOperatorId(), e.getBouncedAt(), e.getCreatedAt());
    }
}
