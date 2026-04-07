package github.lms.lemuel.shipping.domain;

/**
 * 배송 상태
 */
public enum DeliveryStatus {
    PREPARING,
    SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELED;

    public static DeliveryStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("배송 상태 값이 비어있습니다.");
        }
        try {
            return DeliveryStatus.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 배송 상태입니다: " + value);
        }
    }
}
