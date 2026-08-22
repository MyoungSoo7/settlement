package github.lms.lemuel.notification.adapter.out.stream

import github.lms.lemuel.notification.application.StreamListener
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import github.lms.lemuel.notification.domain.StreamEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The hub's bounds. An always-on process must not grow without limit, and the
 * bounds must give way in the right order: a recipient with a live subscriber
 * keeps its resume window, an idle one does not.
 */
class InMemoryNotificationStreamLimitsTest {

    private fun notification(recipient: String, subject: String = "s") =
        Notification(NotificationType.GENERIC, recipient, subject, "body")

    private class Recorder : StreamListener {
        val events = CopyOnWriteArrayList<StreamEvent>()
        override fun onEvent(event: StreamEvent) {
            events += event
        }
    }

    @Test
    fun `subscribing without an identity is refused`() {
        val stream = InMemoryNotificationStream()

        // An empty identity set would mean "subscribed to nothing" — silently
        // delivering nothing forever is worse than failing here.
        assertThrows<IllegalArgumentException> {
            stream.subscribe(emptySet(), null, Recorder())
        }
    }

    @Test
    fun `the replay window is bounded per recipient`() {
        val stream = InMemoryNotificationStream(bufferPerRecipient = 2)

        repeat(5) { stream.publish(notification("seller-1", subject = "s$it")) }

        val recorder = Recorder()
        stream.subscribe(setOf("seller-1"), lastEventId = 0, listener = recorder)

        // Only the last two survive; the client resumes with a visible id gap
        // rather than the hub holding everything forever.
        assertEquals(listOf("s3", "s4"), recorder.events.map { it.notification.subject })
    }

    @Test
    fun `idle retention buffers are pruned once too many recipients are tracked`() {
        val stream = InMemoryNotificationStream(bufferPerRecipient = 5, maxRecipients = 2)

        // Four idle recipients, published oldest first.
        listOf("a", "b", "c", "d").forEach { stream.publish(notification(it)) }

        // The coldest are gone: resuming on "a" replays nothing.
        val cold = Recorder()
        stream.subscribe(setOf("a"), lastEventId = 0, listener = cold)
        assertTrue(cold.events.isEmpty(), "the coldest buffer should have been pruned")

        // The newest is still there.
        val warm = Recorder()
        stream.subscribe(setOf("d"), lastEventId = 0, listener = warm)
        assertEquals(1, warm.events.size)
    }

    @Test
    fun `a recipient with a live subscriber keeps its replay window`() {
        val stream = InMemoryNotificationStream(bufferPerRecipient = 5, maxRecipients = 1)
        val live = Recorder()
        stream.subscribe(setOf("live"), lastEventId = null, listener = live)

        stream.publish(notification("live", subject = "kept"))
        listOf("x", "y", "z").forEach { stream.publish(notification(it)) }

        // Reconnecting on "live" must still replay — dropping it would break the
        // resume guarantee for a client that is actually connected.
        val resumed = Recorder()
        stream.subscribe(setOf("live"), lastEventId = 0, listener = resumed)
        assertEquals(listOf("kept"), resumed.events.map { it.notification.subject })
    }

    @Test
    fun `a subscriber mailbox that overflows drops the oldest and keeps flowing`() {
        val stream = InMemoryNotificationStream(maxPendingPerSubscriber = 2)
        val delivered = CopyOnWriteArrayList<Long>()

        // A listener that publishes while being delivered to: the new events
        // queue behind the one in flight, so the mailbox actually grows.
        lateinit var reentrant: StreamListener
        var burst = false
        reentrant = StreamListener { event ->
            delivered += event.seq
            if (!burst) {
                burst = true
                repeat(6) { stream.publish(notification("seller-1", subject = "burst$it")) }
            }
        }
        stream.subscribe(setOf("seller-1"), lastEventId = null, listener = reentrant)

        stream.publish(notification("seller-1", subject = "first"))

        // 1 (first) + 6 published re-entrantly, but the mailbox only holds 2 at a
        // time — the client sees an id gap instead of the hub buffering forever.
        assertTrue(delivered.size in 3..7, "unexpected delivery count: $delivered")
        assertTrue(delivered.size < 7, "nothing was dropped — the bound did not apply: $delivered")
        assertEquals(delivered.sorted(), delivered.toList(), "delivery must stay ordered: $delivered")
    }

    @Test
    fun `a cancelled subscription stops receiving and is no longer counted`() {
        val stream = InMemoryNotificationStream()
        val recorder = Recorder()
        val subscription = stream.subscribe(setOf("seller-1"), null, recorder)
        assertEquals(1, stream.subscriberCount())

        subscription.cancel()
        stream.publish(notification("seller-1"))

        assertEquals(0, stream.subscriberCount())
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `a subscriber whose write fails is dropped instead of failing the publish`() {
        val stream = InMemoryNotificationStream()
        val healthy = Recorder()
        stream.subscribe(setOf("seller-1"), null, healthy)
        stream.subscribe(setOf("seller-1"), null) { throw IllegalStateException("client gone") }

        stream.publish(notification("seller-1"))

        // One dead browser tab must never stop delivery to everyone else.
        assertEquals(1, healthy.events.size)
        assertEquals(1, stream.subscriberCount())
    }
}
