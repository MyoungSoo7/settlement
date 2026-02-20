package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.springframework.stereotype.Component;

/**
 * Domain <-> JpaEntity 매핑
 */
@Component
public class SettlementPersistenceMapper {

    public Settlement toDomain(SettlementJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new Settlement(
                entity.getId(),
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getPaymentAmount(),
                entity.getCommission(),
                entity.getNetAmount(),
                SettlementStatus.fromString(entity.getStatus()),
                entity.getSettlementDate(),
                entity.getConfirmedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public SettlementJpaEntity toEntity(Settlement domain) {
        if (domain == null) {
            return null;
        }

        SettlementJpaEntity entity = new SettlementJpaEntity();
        entity.setId(domain.getId());
        entity.setPaymentId(domain.getPaymentId());
        entity.setOrderId(domain.getOrderId());
        entity.setPaymentAmount(domain.getPaymentAmount());
        entity.setCommission(domain.getCommission());
        entity.setNetAmount(domain.getNetAmount());
        entity.setStatus(domain.getStatus().name());
        entity.setSettlementDate(domain.getSettlementDate());
        entity.setConfirmedAt(domain.getConfirmedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());

        return entity;
    }
}
