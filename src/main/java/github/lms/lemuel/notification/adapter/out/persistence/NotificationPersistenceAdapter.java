package github.lms.lemuel.notification.adapter.out.persistence;

import github.lms.lemuel.notification.application.port.out.LoadNotificationPort;
import github.lms.lemuel.notification.application.port.out.SaveNotificationPort;
import github.lms.lemuel.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Notification Persistence Adapter
 */
@Component
@RequiredArgsConstructor
public class NotificationPersistenceAdapter implements LoadNotificationPort, SaveNotificationPort {

    private final SpringDataNotificationRepository notificationRepository;

    @Override
    public Optional<Notification> findById(Long id) {
        return notificationRepository.findById(id)
                .map(NotificationPersistenceMapper::toDomain);
    }

    @Override
    public List<Notification> findByUserId(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(NotificationPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Notification> findByUserIdAndReadAtIsNull(Long userId) {
        return notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId)
                .stream()
                .map(NotificationPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public long countByUserIdAndReadAtIsNull(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Override
    public Notification save(Notification notification) {
        NotificationJpaEntity entity = NotificationPersistenceMapper.toEntity(notification);
        NotificationJpaEntity saved = notificationRepository.save(entity);
        return NotificationPersistenceMapper.toDomain(saved);
    }

    @Override
    public List<Notification> saveAll(List<Notification> notifications) {
        List<NotificationJpaEntity> entities = notifications.stream()
                .map(NotificationPersistenceMapper::toEntity)
                .collect(Collectors.toList());
        List<NotificationJpaEntity> saved = notificationRepository.saveAll(entities);
        return saved.stream()
                .map(NotificationPersistenceMapper::toDomain)
                .collect(Collectors.toList());
    }

    public void markAllAsReadByUserId(Long userId) {
        notificationRepository.markAllAsReadByUserId(userId);
    }
}
