package github.lms.lemuel.chargeback.adapter.out.persistence;

import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.application.port.out.SaveChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ChargebackPersistenceAdapter implements LoadChargebackPort, SaveChargebackPort {

    private final SpringDataChargebackRepository repository;

    public ChargebackPersistenceAdapter(SpringDataChargebackRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Chargeback> findById(Long id) {
        return repository.findById(id).map(ChargebackPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Chargeback> findByPgChargebackId(String pgChargebackId) {
        if (pgChargebackId == null) return Optional.empty();
        return repository.findByPgChargebackId(pgChargebackId).map(ChargebackPersistenceAdapter::toDomain);
    }

    @Override
    public List<Chargeback> findByPaymentId(Long paymentId) {
        return repository.findByPaymentIdOrderByRaisedAtDesc(paymentId).stream()
                .map(ChargebackPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Chargeback> findByStatus(ChargebackStatus status, int limit) {
        return repository.findByStatusOrderByRaisedAtDesc(status, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(ChargebackPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Chargeback save(Chargeback chargeback) {
        ChargebackJpaEntity entity;
        if (chargeback.getId() == null) {
            entity = new ChargebackJpaEntity(
                    null,
                    chargeback.getPaymentId(),
                    chargeback.getSettlementId(),
                    chargeback.getAmount(),
                    chargeback.getReasonCode(),
                    chargeback.getReasonDetail(),
                    chargeback.getStatus(),
                    chargeback.getSource(),
                    chargeback.getPgChargebackId(),
                    chargeback.getDecidedBy(),
                    chargeback.getDecisionNote(),
                    chargeback.getRaisedAt(),
                    chargeback.getDecidedAt(),
                    chargeback.getCreatedAt(),
                    chargeback.getUpdatedAt()
            );
        } else {
            entity = repository.findById(chargeback.getId())
                    .orElseThrow(() -> new IllegalStateException("Chargeback not found: " + chargeback.getId()));
            entity.applyDecision(
                    chargeback.getStatus(),
                    chargeback.getSettlementId(),
                    chargeback.getDecidedBy(),
                    chargeback.getDecisionNote(),
                    chargeback.getDecidedAt(),
                    chargeback.getUpdatedAt()
            );
        }
        return toDomain(repository.save(entity));
    }

    private static Chargeback toDomain(ChargebackJpaEntity e) {
        return Chargeback.rehydrate(
                e.getId(), e.getPaymentId(), e.getSettlementId(), e.getAmount(),
                e.getReasonCode(), e.getReasonDetail(),
                e.getSource(), e.getPgChargebackId(),
                e.getStatus(), e.getDecidedBy(), e.getDecisionNote(),
                e.getRaisedAt(), e.getDecidedAt(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
