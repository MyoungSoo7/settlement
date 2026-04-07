package github.lms.lemuel.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataDeliveryRepository extends JpaRepository<DeliveryJpaEntity, Long> {

    Optional<DeliveryJpaEntity> findByOrderId(Long orderId);

    List<DeliveryJpaEntity> findByStatus(String status);

    Optional<DeliveryJpaEntity> findByTrackingNumber(String trackingNumber);
}
