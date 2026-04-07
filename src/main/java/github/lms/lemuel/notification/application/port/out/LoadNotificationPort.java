package github.lms.lemuel.notification.application.port.out;

import github.lms.lemuel.notification.domain.Notification;

import java.util.List;
import java.util.Optional;

public interface LoadNotificationPort {

    Optional<Notification> findById(Long id);

    List<Notification> findByUserId(Long userId);

    List<Notification> findByUserIdAndReadAtIsNull(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);
}
