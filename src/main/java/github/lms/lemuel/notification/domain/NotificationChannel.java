package github.lms.lemuel.notification.domain;

/**
 * 알림 채널 Enum
 */
public enum NotificationChannel {
    EMAIL,
    IN_APP,
    SMS,
    PUSH;

    public static NotificationChannel fromString(String channel) {
        try {
            return NotificationChannel.valueOf(channel.toUpperCase());
        } catch (Exception e) {
            return EMAIL;
        }
    }
}
