package github.lms.lemuel.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataShipmentRepository extends JpaRepository<ShipmentJpaEntity, Long> {

    Optional<ShipmentJpaEntity> findByOrderId(Long orderId);

    Optional<ShipmentJpaEntity> findByCarrierAndTrackingNumber(String carrier, String trackingNumber);
}
