package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.domain.Delivery;
import github.lms.lemuel.shipping.domain.DeliveryStatus;
import github.lms.lemuel.shipping.domain.ShippingAddress;

/**
 * 배송 도메인 <-> JPA 엔티티 수동 매퍼
 */
public class ShippingPersistenceMapper {

    private ShippingPersistenceMapper() {}

    // ── ShippingAddress ─────────────────────────────────────────────────

    public static ShippingAddressJpaEntity toEntity(ShippingAddress domain) {
        ShippingAddressJpaEntity entity = new ShippingAddressJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setRecipientName(domain.getRecipientName());
        entity.setPhone(domain.getPhone());
        entity.setZipCode(domain.getZipCode());
        entity.setAddress(domain.getAddress());
        entity.setAddressDetail(domain.getAddressDetail());
        entity.setDefault(domain.isDefault());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public static ShippingAddress toDomain(ShippingAddressJpaEntity entity) {
        ShippingAddress domain = new ShippingAddress();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setRecipientName(entity.getRecipientName());
        domain.setPhone(entity.getPhone());
        domain.setZipCode(entity.getZipCode());
        domain.setAddress(entity.getAddress());
        domain.setAddressDetail(entity.getAddressDetail());
        domain.setDefault(entity.isDefault());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    // ── Delivery ────────────────────────────────────────────────────────

    public static DeliveryJpaEntity toEntity(Delivery domain) {
        DeliveryJpaEntity entity = new DeliveryJpaEntity();
        entity.setId(domain.getId());
        entity.setOrderId(domain.getOrderId());
        entity.setAddressId(domain.getAddressId());
        entity.setStatus(domain.getStatus().name());
        entity.setTrackingNumber(domain.getTrackingNumber());
        entity.setCarrier(domain.getCarrier());
        entity.setRecipientName(domain.getRecipientName());
        entity.setPhone(domain.getPhone());
        entity.setAddress(domain.getAddress());
        entity.setShippingFee(domain.getShippingFee());
        entity.setShippedAt(domain.getShippedAt());
        entity.setDeliveredAt(domain.getDeliveredAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public static Delivery toDomain(DeliveryJpaEntity entity) {
        Delivery domain = new Delivery();
        domain.setId(entity.getId());
        domain.setOrderId(entity.getOrderId());
        domain.setAddressId(entity.getAddressId());
        domain.setStatus(DeliveryStatus.fromString(entity.getStatus()));
        domain.setTrackingNumber(entity.getTrackingNumber());
        domain.setCarrier(entity.getCarrier());
        domain.setRecipientName(entity.getRecipientName());
        domain.setPhone(entity.getPhone());
        domain.setAddress(entity.getAddress());
        domain.setShippingFee(entity.getShippingFee());
        domain.setShippedAt(entity.getShippedAt());
        domain.setDeliveredAt(entity.getDeliveredAt());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }
}
