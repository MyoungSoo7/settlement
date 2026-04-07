package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.Delivery;
import github.lms.lemuel.shipping.domain.DeliveryStatus;

import java.util.List;
import java.util.Optional;

public interface LoadDeliveryPort {

    Optional<Delivery> findById(Long id);

    Optional<Delivery> findByOrderId(Long orderId);

    List<Delivery> findByStatus(DeliveryStatus status);

    Optional<Delivery> findByTrackingNumber(String trackingNumber);
}
