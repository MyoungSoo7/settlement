package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementAdjustmentStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class SettlementAdjustmentPersistenceAdapter implements SaveSettlementAdjustmentPort {

    private final SpringDataSettlementAdjustmentJpaRepository repository;

    public SettlementAdjustmentPersistenceAdapter(SpringDataSettlementAdjustmentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public SettlementAdjustment save(SettlementAdjustment domain) {
        SettlementAdjustmentJpaEntity entity = toEntity(domain);
        SettlementAdjustmentJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    public boolean existsByReconciliationDiscrepancyId(Long discrepancyId) {
        if (discrepancyId == null) {
            return false;
        }
        return repository.existsByReconciliationDiscrepancyId(discrepancyId);
    }

    private SettlementAdjustmentJpaEntity toEntity(SettlementAdjustment domain) {
        SettlementAdjustmentJpaEntity entity = new SettlementAdjustmentJpaEntity();
        entity.setId(domain.getId());
        entity.setSettlementId(domain.getSettlementId());
        entity.setRefundId(domain.getRefundId());
        entity.setChargebackId(domain.getChargebackId());
        entity.setReconciliationDiscrepancyId(domain.getReconciliationDiscrepancyId());
        entity.setAmount(domain.getAmount());
        entity.setStatus(domain.getStatus().name());
        entity.setAdjustmentDate(domain.getAdjustmentDate());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(domain.getCreatedAt() != null ? domain.getCreatedAt() : now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private SettlementAdjustment toDomain(SettlementAdjustmentJpaEntity entity) {
        return SettlementAdjustment.rehydrate(
                entity.getId(),
                entity.getSettlementId(),
                entity.getRefundId(),
                entity.getChargebackId(),
                entity.getReconciliationDiscrepancyId(),
                entity.getAmount(),
                SettlementAdjustmentStatus.fromString(entity.getStatus()),
                entity.getAdjustmentDate(),
                entity.getCreatedAt());
    }
}
