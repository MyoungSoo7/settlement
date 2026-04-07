package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ReservationStatus;
import github.lms.lemuel.product.domain.StockReservation;
import org.springframework.stereotype.Component;

@Component
public class StockReservationPersistenceMapper {

    public StockReservationJpaEntity toJpaEntity(StockReservation domain) {
        if (domain == null) {
            return null;
        }

        return new StockReservationJpaEntity(
                domain.getId(),
                domain.getProductId(),
                domain.getOrderId(),
                domain.getUserId(),
                domain.getQuantity(),
                domain.getStatus() != null ? domain.getStatus().name() : ReservationStatus.RESERVED.name(),
                domain.getExpiresAt(),
                domain.getConfirmedAt(),
                domain.getReleasedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public StockReservation toDomainEntity(StockReservationJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        return new StockReservation(
                entity.getId(),
                entity.getProductId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getQuantity(),
                ReservationStatus.fromString(entity.getStatus()),
                entity.getExpiresAt(),
                entity.getConfirmedAt(),
                entity.getReleasedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
