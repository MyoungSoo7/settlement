package github.lms.lemuel.notification.application.port.in;

import github.lms.lemuel.notification.domain.Notification;
import github.lms.lemuel.notification.domain.NotificationChannel;
import github.lms.lemuel.notification.domain.NotificationType;

import java.util.List;

public interface NotificationUseCase {

    Notification send(SendNotificationCommand cmd);

    List<Notification> getUserNotifications(Long userId);

    List<Notification> getUnreadNotifications(Long userId);

    long getUnreadCount(Long userId);

    Notification markAsRead(Long notificationId);

    void markAllAsRead(Long userId);

    record SendNotificationCommand(
            Long userId,
            NotificationType type,
            NotificationChannel channel,
            String title,
            String content,
            String referenceType,
            Long referenceId
    ) {
        public SendNotificationCommand {
            if (userId == null) {
                throw new IllegalArgumentException("User ID cannot be null");
            }
            if (type == null) {
                throw new IllegalArgumentException("Notification type cannot be null");
            }
            if (channel == null) {
                throw new IllegalArgumentException("Notification channel cannot be null");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Title cannot be empty");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Content cannot be empty");
            }
        }
    }
}
