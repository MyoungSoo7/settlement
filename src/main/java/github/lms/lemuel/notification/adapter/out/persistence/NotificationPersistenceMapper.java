package github.lms.lemuel.notification.adapter.out.persistence;

import github.lms.lemuel.notification.domain.Notification;
import github.lms.lemuel.notification.domain.NotificationChannel;
import github.lms.lemuel.notification.domain.NotificationStatus;
import github.lms.lemuel.notification.domain.NotificationType;

/**
 * 알림 도메인 <-> JPA 엔티티 수동 매퍼
 */
public class NotificationPersistenceMapper {

    private NotificationPersistenceMapper() {}

    public static NotificationJpaEntity toEntity(Notification domain) {
        NotificationJpaEntity entity = new NotificationJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setType(domain.getType().name());
        entity.setChannel(domain.getChannel().name());
        entity.setTitle(domain.getTitle());
        entity.setContent(domain.getContent());
        entity.setStatus(domain.getStatus().name());
        entity.setReferenceType(domain.getReferenceType());
        entity.setReferenceId(domain.getReferenceId());
        entity.setSentAt(domain.getSentAt());
        entity.setReadAt(domain.getReadAt());
        entity.setFailedAt(domain.getFailedAt());
        entity.setFailureReason(domain.getFailureReason());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public static Notification toDomain(NotificationJpaEntity entity) {
        Notification domain = new Notification();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setType(NotificationType.fromString(entity.getType()));
        domain.setChannel(NotificationChannel.fromString(entity.getChannel()));
        domain.setTitle(entity.getTitle());
        domain.setContent(entity.getContent());
        domain.setStatus(NotificationStatus.fromString(entity.getStatus()));
        domain.setReferenceType(entity.getReferenceType());
        domain.setReferenceId(entity.getReferenceId());
        domain.setSentAt(entity.getSentAt());
        domain.setReadAt(entity.getReadAt());
        domain.setFailedAt(entity.getFailedAt());
        domain.setFailureReason(entity.getFailureReason());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }
}
