package github.lms.lemuel.notification.adapter.in.web;

import github.lms.lemuel.notification.adapter.in.web.response.NotificationResponse;
import github.lms.lemuel.notification.application.port.in.NotificationUseCase;
import github.lms.lemuel.notification.domain.Notification;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Notification API Controller
 */
@Validated
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationUseCase notificationUseCase;

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<NotificationResponse>> getUserNotifications(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<NotificationResponse> notifications = notificationUseCase.getUserNotifications(userId)
                .stream()
                .map(NotificationResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(notifications);
    }

    @GetMapping("/user/{userId}/unread")
    public ResponseEntity<List<NotificationResponse>> getUnreadNotifications(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<NotificationResponse> notifications = notificationUseCase.getUnreadNotifications(userId)
                .stream()
                .map(NotificationResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(notifications);
    }

    @GetMapping("/user/{userId}/unread/count")
    public ResponseEntity<Long> getUnreadCount(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        long count = notificationUseCase.getUnreadCount(userId);
        return ResponseEntity.ok(count);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markAsRead(
            @PathVariable @Positive(message = "알림 ID는 양수여야 합니다") Long id) {
        Notification notification = notificationUseCase.markAsRead(id);
        return ResponseEntity.ok(NotificationResponse.from(notification));
    }

    @PatchMapping("/user/{userId}/read-all")
    public ResponseEntity<Void> markAllAsRead(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        notificationUseCase.markAllAsRead(userId);
        return ResponseEntity.ok().build();
    }
}
