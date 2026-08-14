package github.lms.lemuel.notification.application

import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.StreamEvent

/**
 * Outbound port: the server→client push stream (the "notification hub").
 *
 * Publishing and subscribing are deliberately in one port — they are two ends
 * of the same fan-out, and separating them would let an implementation satisfy
 * one without the other (a publisher nobody can subscribe to).
 */
interface NotificationStream {

    /**
     * Records a notification on the stream and pushes it to every subscriber
     * listening for its recipient. Returns the event with its assigned
     * sequence number.
     */
    fun publish(notification: Notification): StreamEvent

    /**
     * Registers a listener for the given [recipients] — the identities a
     * client is allowed to receive (derived from its JWT, never from a request
     * parameter).
     *
     * @param lastEventId the client's resume point. `null` = live only;
     *   otherwise every retained event with a greater sequence is replayed
     *   first, in order, before live events resume. Retention is bounded, so a
     *   long absence returns fewer events than were missed rather than all of
     *   them.
     */
    fun subscribe(
        recipients: Set<String>,
        lastEventId: Long?,
        listener: StreamListener,
    ): StreamSubscription

    /** Live subscriber count — for metrics and tests. */
    fun subscriberCount(): Int
}

/**
 * Receives events for one subscriber. Implementations may throw to signal a
 * dead client (e.g. a broken SSE connection); the stream then drops that
 * subscriber.
 */
fun interface StreamListener {
    fun onEvent(event: StreamEvent)
}

/** Handle to release a subscription. Cancelling twice is a no-op. */
interface StreamSubscription {
    fun cancel()
}
