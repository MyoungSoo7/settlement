package github.lms.lemuel.notification.domain;

/**
 * 알림 유형 Enum
 */
public enum NotificationType {
    ORDER_CREATED,
    ORDER_PAID,
    ORDER_CANCELED,
    PAYMENT_COMPLETED,
    PAYMENT_REFUNDED,
    DELIVERY_SHIPPED,
    DELIVERY_DELIVERED,
    RETURN_APPROVED,
    RETURN_COMPLETED,
    SETTLEMENT_CONFIRMED,
    GENERAL;

    public static NotificationType fromString(String type) {
        try {
            return NotificationType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            return GENERAL;
        }
    }
}
