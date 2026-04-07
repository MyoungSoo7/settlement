package github.lms.lemuel.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data JPA Repository for Notification
 */
public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, Long> {

    List<NotificationJpaEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<NotificationJpaEntity> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying
    @Query("UPDATE NotificationJpaEntity n SET n.readAt = CURRENT_TIMESTAMP, n.status = 'READ', n.updatedAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.readAt IS NULL")
    void markAllAsReadByUserId(@Param("userId") Long userId);
}
