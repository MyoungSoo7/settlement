package github.lms.lemuel.notification.application

import github.lms.lemuel.notification.domain.Notification

/**
 * Inbound use-case port. Web/Kafka adapters depend on this interface instead of
 * the concrete [NotificationDispatcher], keeping the dependency direction
 * adapter → application-port (DIP) and letting tests substitute the core.
 */
interface DispatchNotificationUseCase {
    /** Fan the notification out to every enabled channel; idempotent per eventId. */
    suspend fun dispatch(notification: Notification): DispatchResult
}
