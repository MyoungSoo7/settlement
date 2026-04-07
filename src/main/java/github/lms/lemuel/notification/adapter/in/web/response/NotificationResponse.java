package github.lms.lemuel.notification.adapter.in.web.response;

import github.lms.lemuel.notification.domain.Notification;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        Long userId,
        String type,
        String channel,
        String title,
        String content,
        String status,
        String referenceType,
        Long referenceId,
        LocalDateTime sentAt,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getType().name(),
                notification.getChannel().name(),
                notification.getTitle(),
                notification.getContent(),
                notification.getStatus().name(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.getSentAt(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
