package github.lms.lemuel.notification.domain;

/**
 * 알림 상태 Enum
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    READ;

    public static NotificationStatus fromString(String status) {
        try {
            return NotificationStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return PENDING;
        }
    }
}
