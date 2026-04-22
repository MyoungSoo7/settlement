package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
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

    private SettlementAdjustmentJpaEntity toEntity(SettlementAdjustment domain) {
        SettlementAdjustmentJpaEntity entity = new SettlementAdjustmentJpaEntity();
        entity.setId(domain.getId());
        entity.setSettlementId(domain.getSettlementId());
        entity.setRefundId(domain.getRefundId());
        entity.setAmount(domain.getAmount());
        entity.setStatus(domain.getStatus() != null ? domain.getStatus() : "PENDING");
        entity.setAdjustmentDate(domain.getAdjustmentDate());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(domain.getCreatedAt() != null ? domain.getCreatedAt() : now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private SettlementAdjustment toDomain(SettlementAdjustmentJpaEntity entity) {
        SettlementAdjustment domain = new SettlementAdjustment();
        domain.setId(entity.getId());
        domain.setSettlementId(entity.getSettlementId());
        domain.setRefundId(entity.getRefundId());
        domain.setAmount(entity.getAmount());
        domain.setStatus(entity.getStatus());
        domain.setAdjustmentDate(entity.getAdjustmentDate());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }
}
