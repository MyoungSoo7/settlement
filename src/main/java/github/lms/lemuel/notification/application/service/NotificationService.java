package github.lms.lemuel.notification.application.service;

import github.lms.lemuel.notification.adapter.out.persistence.NotificationPersistenceAdapter;
import github.lms.lemuel.notification.application.port.in.NotificationUseCase;
import github.lms.lemuel.notification.application.port.out.LoadNotificationPort;
import github.lms.lemuel.notification.application.port.out.SaveNotificationPort;
import github.lms.lemuel.notification.application.port.out.SendNotificationPort;
import github.lms.lemuel.notification.domain.Notification;
import github.lms.lemuel.notification.domain.NotificationChannel;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 서비스
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationService implements NotificationUseCase {

    private final LoadNotificationPort loadNotificationPort;
    private final SaveNotificationPort saveNotificationPort;
    private final SendNotificationPort sendNotificationPort;
    private final LoadUserPort loadUserPort;
    private final NotificationPersistenceAdapter notificationPersistenceAdapter;

    @Override
    public Notification send(SendNotificationCommand cmd) {
        Notification notification = Notification.create(
                cmd.userId(),
                cmd.type(),
                cmd.channel(),
                cmd.title(),
                cmd.content(),
                cmd.referenceType(),
                cmd.referenceId()
        );

        // 먼저 저장
        notification = saveNotificationPort.save(notification);

        // 채널별 전송 처리
        if (cmd.channel() == NotificationChannel.EMAIL) {
            User user = loadUserPort.findById(cmd.userId())
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + cmd.userId()));

            boolean sent = sendNotificationPort.sendEmail(user.getEmail(), cmd.title(), cmd.content());

            if (sent) {
                notification.markAsSent();
            } else {
                notification.markAsFailed("이메일 전송 실패");
            }

            notification = saveNotificationPort.save(notification);
        } else if (cmd.channel() == NotificationChannel.IN_APP) {
            // IN_APP은 저장만 하면 됨 (전송 불필요)
            notification.markAsSent();
            notification = saveNotificationPort.save(notification);
        } else {
            // SMS, PUSH 등 미구현 채널
            log.warn("미지원 알림 채널: {}", cmd.channel());
            notification.markAsFailed("미지원 채널: " + cmd.channel());
            notification = saveNotificationPort.save(notification);
        }

        return notification;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> getUserNotifications(Long userId) {
        return loadNotificationPort.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> getUnreadNotifications(Long userId) {
        return loadNotificationPort.findByUserIdAndReadAtIsNull(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return loadNotificationPort.countByUserIdAndReadAtIsNull(userId);
    }

    @Override
    public Notification markAsRead(Long notificationId) {
        Notification notification = loadNotificationPort.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다: " + notificationId));

        notification.markAsRead();
        return saveNotificationPort.save(notification);
    }

    @Override
    public void markAllAsRead(Long userId) {
        notificationPersistenceAdapter.markAllAsReadByUserId(userId);
    }
}
