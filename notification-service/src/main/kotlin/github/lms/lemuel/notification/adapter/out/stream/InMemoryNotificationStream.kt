package github.lms.lemuel.notification.adapter.out.stream

import github.lms.lemuel.notification.application.NotificationStream
import github.lms.lemuel.notification.application.StreamListener
import github.lms.lemuel.notification.application.StreamSubscription
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.StreamEvent
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory implementation of the [NotificationStream] port — an outbound
 * adapter, so the application layer stays implementation-free.
 *
 * Design:
 *  - **One lock** guards the sequence, the per-recipient retention buffers and
 *    the subscriber index. Listener calls (SSE writes) happen OUTSIDE it, so a
 *    stalled browser can never block publishers.
 *  - **Per-subscriber mailbox + a non-reentrant drain flag** give each
 *    subscriber strictly ordered delivery: whoever wins the flag drains the
 *    mailbox, everyone else just enqueues. A listener that publishes while
 *    being delivered to (re-entrancy) queues behind the events already in
 *    flight instead of jumping the line.
 *  - **Retention is bounded** per recipient (replay window) and across
 *    recipients (idle entries are pruned), so an always-on process cannot grow
 *    without limit.
 *
 * Limits, deliberate for the MVP: state is per process. A restart loses the
 * replay window, and with more than one replica a client only resumes what the
 * replica it reconnects to happens to hold (see docs/sse.md).
 */
class InMemoryNotificationStream(
    private val bufferPerRecipient: Int = 100,
    private val maxRecipients: Int = 10_000,
    private val maxPendingPerSubscriber: Int = 200,
    private val clock: () -> Instant = Instant::now,
) : NotificationStream {

    private val log = LoggerFactory.getLogger(javaClass)

    private val lock = ReentrantLock()

    /** Guarded by [lock]. */
    private var seq = 0L

    /**
     * recipient → retained events (oldest first). LinkedHashMap in
     * least-recently-published order so pruning can start at the front.
     * Guarded by [lock].
     */
    private val buffers = LinkedHashMap<String, ArrayDeque<StreamEvent>>()

    /** recipient → subscribers listening under that identity. Guarded by [lock]. */
    private val byRecipient = HashMap<String, MutableSet<Subscriber>>()

    /** Guarded by [lock]. */
    private val subscribers = LinkedHashSet<Subscriber>()

    override fun publish(notification: Notification): StreamEvent {
        val event: StreamEvent
        val targets: List<Subscriber>
        lock.withLock {
            event = StreamEvent(++seq, notification, clock())
            retainLocked(event)
            targets = byRecipient[notification.recipient].orEmpty().toList()
            targets.forEach { enqueueLocked(it, event) }
        }
        // Delivery happens off-lock; each subscriber is drained in order.
        targets.forEach { pump(it) }
        return event
    }

    override fun subscribe(
        recipients: Set<String>,
        lastEventId: Long?,
        listener: StreamListener,
    ): StreamSubscription {
        require(recipients.isNotEmpty()) { "at least one recipient identity is required" }

        val subscriber = Subscriber(recipients.toSet(), listener)
        lock.withLock {
            // Registering and loading the backlog under ONE lock is what makes
            // resume gapless: an event published concurrently either lands in
            // the backlog snapshot or in the mailbox behind it — never both,
            // never neither.
            subscribers += subscriber
            recipients.forEach { byRecipient.getOrPut(it) { LinkedHashSet() } += subscriber }
            if (lastEventId != null) {
                recipients
                    .flatMap { buffers[it].orEmpty() }
                    .filter { it.seq > lastEventId }
                    .sortedBy { it.seq }
                    .forEach { enqueueLocked(subscriber, it) }
            }
        }
        pump(subscriber)
        return Subscription(subscriber)
    }

    override fun subscriberCount(): Int = lock.withLock { subscribers.size }

    // --- retention -----------------------------------------------------------

    /** Caller holds [lock]. */
    private fun retainLocked(event: StreamEvent) {
        // remove+put keeps LinkedHashMap ordered by last publish, so the
        // eldest entries are the coldest ones.
        val buffer = buffers.remove(event.recipient) ?: ArrayDeque(bufferPerRecipient)
        buffer.addLast(event)
        while (buffer.size > bufferPerRecipient) {
            buffer.removeFirst()
        }
        buffers[event.recipient] = buffer
        pruneLocked()
    }

    /**
     * Drops the coldest retention buffers once too many recipients are tracked.
     * Recipients with a live subscriber are never dropped — their resume window
     * must stay valid. Caller holds [lock].
     */
    private fun pruneLocked() {
        if (buffers.size <= maxRecipients) return
        val victims = buffers.keys
            .asSequence()
            .filter { byRecipient[it].isNullOrEmpty() }
            .take(buffers.size - maxRecipients)
            .toList()
        victims.forEach { buffers.remove(it) }
        if (victims.isNotEmpty()) {
            log.debug("pruned {} idle retention buffers", victims.size)
        }
    }

    // --- delivery ------------------------------------------------------------

    /** Caller holds [lock]. */
    private fun enqueueLocked(subscriber: Subscriber, event: StreamEvent) {
        subscriber.mailbox.addLast(event)
        while (subscriber.mailbox.size > maxPendingPerSubscriber) {
            val dropped = subscriber.mailbox.removeFirst()
            log.warn(
                "subscriber mailbox full ({}), dropped seq={} — the client will see an id gap",
                maxPendingPerSubscriber, dropped.seq,
            )
        }
    }

    /**
     * Drains a subscriber's mailbox in sequence order. The drain flag is a
     * plain (non-reentrant) CAS on purpose: if a listener publishes while being
     * delivered to, the new event must queue behind the ones already in flight
     * instead of being delivered nested, out of order.
     */
    private fun pump(subscriber: Subscriber) {
        while (true) {
            if (!subscriber.draining.compareAndSet(false, true)) return
            try {
                while (true) {
                    val event = lock.withLock { subscriber.mailbox.removeFirstOrNull() } ?: break
                    if (!deliver(subscriber, event)) return
                }
            } finally {
                subscriber.draining.set(false)
            }
            // Something may have arrived between the last poll and the release.
            if (lock.withLock { subscriber.mailbox.isEmpty() }) return
        }
    }

    /** @return false if the subscriber is gone (cancelled or failed). */
    private fun deliver(subscriber: Subscriber, event: StreamEvent): Boolean {
        if (subscriber.cancelled) return false
        return try {
            subscriber.listener.onEvent(event)
            true
        } catch (e: Exception) {
            // A failed write means the client is gone (closed tab, dead proxy).
            // Drop it — never let one dead subscriber fail a publish.
            log.info("dropping subscriber after delivery failure: {}", e.toString())
            remove(subscriber)
            false
        }
    }

    private fun remove(subscriber: Subscriber) {
        lock.withLock {
            subscriber.cancelled = true
            subscribers -= subscriber
            subscriber.recipients.forEach { recipient ->
                val subs = byRecipient[recipient] ?: return@forEach
                subs -= subscriber
                if (subs.isEmpty()) byRecipient.remove(recipient)
            }
            subscriber.mailbox.clear()
        }
    }

    private class Subscriber(
        val recipients: Set<String>,
        val listener: StreamListener,
    ) {
        /** Guarded by the stream lock. */
        val mailbox = ArrayDeque<StreamEvent>()
        val draining = AtomicBoolean(false)

        @Volatile
        var cancelled = false
    }

    private inner class Subscription(private val subscriber: Subscriber) : StreamSubscription {
        override fun cancel() = remove(subscriber)
    }
}
