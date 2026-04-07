package github.lms.lemuel.shipping.adapter.in.web.dto;

import github.lms.lemuel.shipping.domain.Delivery;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeliveryResponse(
        Long id,
        Long orderId,
        String status,
        String trackingNumber,
        String carrier,
        String recipientName,
        String phone,
        String address,
        BigDecimal shippingFee,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        LocalDateTime createdAt
) {
    public static DeliveryResponse from(Delivery domain) {
        return new DeliveryResponse(
                domain.getId(),
                domain.getOrderId(),
                domain.getStatus().name(),
                domain.getTrackingNumber(),
                domain.getCarrier(),
                domain.getRecipientName(),
                domain.getPhone(),
                domain.getAddress(),
                domain.getShippingFee(),
                domain.getShippedAt(),
                domain.getDeliveredAt(),
                domain.getCreatedAt()
        );
    }
}
