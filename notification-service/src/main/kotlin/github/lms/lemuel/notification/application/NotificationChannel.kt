package github.lms.lemuel.notification.application

import github.lms.lemuel.notification.domain.Notification

/**
 * Outbound port. A channel knows how to deliver a Notification.
 * `suspend` so the dispatcher can fan out concurrently without blocking threads.
 */
interface NotificationChannel {
    /** Stable channel name, e.g. "log", "slack", "email". */
    val name: String

    /** Whether this channel is currently enabled (config present). */
    val enabled: Boolean

    /** Deliver the notification. Throw on failure — the dispatcher handles retry/timeout. */
    suspend fun send(notification: Notification)
}
