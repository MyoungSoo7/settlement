package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.Delivery;
import github.lms.lemuel.shipping.domain.DeliveryStatus;

import java.math.BigDecimal;
import java.util.List;

public interface DeliveryUseCase {

    Delivery createDelivery(CreateDeliveryCommand command);

    Delivery shipDelivery(Long deliveryId, String trackingNumber, String carrier);

    Delivery updateStatus(Long deliveryId, DeliveryStatus status);

    Delivery getDelivery(Long id);

    Delivery getDeliveryByOrderId(Long orderId);

    List<Delivery> getDeliveriesByStatus(DeliveryStatus status);

    record CreateDeliveryCommand(
            Long orderId,
            Long addressId,
            String recipientName,
            String phone,
            String address,
            BigDecimal shippingFee
    ) {}
}
