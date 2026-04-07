package github.lms.lemuel.notification.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Notification Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 */
@Getter
@Setter
public class Notification {

    private Long id;
    private Long userId;
    private NotificationType type;
    private NotificationChannel channel;
    private String title;
    private String content;
    private NotificationStatus status;
    private String referenceType;
    private Long referenceId;
    private LocalDateTime sentAt;
    private LocalDateTime readAt;
    private LocalDateTime failedAt;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public Notification() {
        this.status = NotificationStatus.PENDING;
        this.channel = NotificationChannel.EMAIL;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public Notification(Long id, Long userId, NotificationType type, NotificationChannel channel,
                        String title, String content, NotificationStatus status,
                        String referenceType, Long referenceId,
                        LocalDateTime sentAt, LocalDateTime readAt,
                        LocalDateTime failedAt, String failureReason,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.channel = channel;
        this.title = title;
        this.content = content;
        this.status = status != null ? status : NotificationStatus.PENDING;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.sentAt = sentAt;
        this.readAt = readAt;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Notification create(Long userId, NotificationType type, NotificationChannel channel,
                                       String title, String content,
                                       String referenceType, Long referenceId) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setChannel(channel);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setReferenceType(referenceType);
        notification.setReferenceId(referenceId);
        return notification;
    }

    // 비즈니스 메서드: 전송 완료 처리
    public void markAsSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 전송 실패 처리
    public void markAsFailed(String reason) {
        this.status = NotificationStatus.FAILED;
        this.failedAt = LocalDateTime.now();
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 읽음 처리
    public void markAsRead() {
        this.status = NotificationStatus.READ;
        this.readAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return this.status == NotificationStatus.PENDING;
    }

    public boolean isSent() {
        return this.status == NotificationStatus.SENT;
    }
}
