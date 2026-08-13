package github.lms.lemuel.notification.domain

import java.time.Instant

/**
 * A notification as it appears on the push stream.
 *
 * [seq] is the client-visible resume point (SSE `id:` / `Last-Event-ID`). It is
 * assigned by the stream, GLOBALLY monotonic rather than per recipient: one
 * subscriber may listen under several identities (user id, email, ops mailbox)
 * and still needs a single ordered id line to resume from.
 *
 * Pure value object — no framework, no I/O.
 */
data class StreamEvent(
    val seq: Long,
    val notification: Notification,
    val occurredAt: Instant,
) {
    init {
        if (seq < 1) {
            throw NotificationInvariantViolationException("seq must be >= 1, was $seq")
        }
    }

    /** Whom this event is addressed to — the routing key of the push hub. */
    val recipient: String get() = notification.recipient
}
