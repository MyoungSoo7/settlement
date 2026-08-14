package github.lms.lemuel.notification.adapter.out.stream

import github.lms.lemuel.notification.application.StreamListener
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import github.lms.lemuel.notification.domain.StreamEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The push hub's contract: per-subscriber routing, ordering, and — the reason
 * it exists — replaying what a disconnected client missed.
 */
class InMemoryNotificationStreamTest {

    private fun notification(recipient: String, subject: String = "s") =
        Notification(NotificationType.GENERIC, recipient, subject, "body")

    /** Records everything delivered, in delivery order. */
    private open class Recorder : StreamListener {
        val events = CopyOnWriteArrayList<StreamEvent>()
        override fun onEvent(event: StreamEvent) {
            events += event
        }

        fun seqs(): List<Long> = events.map { it.seq }
        fun subjects(): List<String> = events.map { it.notification.subject }
    }

    @Test
    fun `publish assigns a monotonic sequence starting at 1`() {
        val stream = InMemoryNotificationStream()

        val first = stream.publish(notification("seller-1"))
        val second = stream.publish(notification("seller-2"))
        val third = stream.publish(notification("seller-1"))

        // Sequence is global, not per recipient: a subscriber may listen to
        // several identities at once and still needs one ordered id line.
        assertEquals(listOf(1L, 2L, 3L), listOf(first.seq, second.seq, third.seq))
    }

    @Test
    fun `live subscriber receives only notifications addressed to it`() {
        val stream = InMemoryNotificationStream()
        val mine = Recorder()
        stream.subscribe(setOf("seller-1"), lastEventId = null, listener = mine)

        stream.publish(notification("seller-1", "mine"))
        stream.publish(notification("seller-2", "not-mine"))
        stream.publish(notification("seller-1", "mine-again"))

        assertEquals(listOf("mine", "mine-again"), mine.subjects())
    }

    @Test
    fun `subscribing without a lastEventId delivers live events only`() {
        val stream = InMemoryNotificationStream()
        stream.publish(notification("seller-1", "before"))

        val late = Recorder()
        stream.subscribe(setOf("seller-1"), lastEventId = null, listener = late)
        stream.publish(notification("seller-1", "after"))

        assertEquals(listOf("after"), late.subjects())
    }

    @Test
    fun `reconnect with lastEventId replays exactly what was missed`() {
        val stream = InMemoryNotificationStream()
        val first = stream.publish(notification("seller-1", "seen"))
        stream.publish(notification("seller-1", "missed-1"))
        stream.publish(notification("seller-2", "someone-else"))
        stream.publish(notification("seller-1", "missed-2"))

        val resumed = Recorder()
        stream.subscribe(setOf("seller-1"), lastEventId = first.seq, listener = resumed)

        assertEquals(listOf("missed-1", "missed-2"), resumed.subjects())
    }

    @Test
    fun `replay is bounded by the retained buffer size`() {
        val stream = InMemoryNotificationStream(bufferPerRecipient = 2)
        repeat(5) { i -> stream.publish(notification("seller-1", "n$i")) }

        val resumed = Recorder()
        stream.subscribe(setOf("seller-1"), lastEventId = 0L, listener = resumed)

        // Only the two newest survive — the client sees a gap in ids rather
        // than a false "you are up to date".
        assertEquals(listOf("n3", "n4"), resumed.subjects())
    }

    @Test
    fun `a subscriber with several identities gets one ordered stream`() {
        val stream = InMemoryNotificationStream()
        stream.publish(notification("42", "by-user-id"))
        stream.publish(notification("seller@lemuel.co.kr", "by-email"))

        val resumed = Recorder()
        stream.subscribe(setOf("42", "seller@lemuel.co.kr"), lastEventId = 0L, listener = resumed)

        assertEquals(listOf("by-user-id", "by-email"), resumed.subjects())
        assertEquals(listOf(1L, 2L), resumed.seqs())
    }

    @Test
    fun `events published during replay are delivered after the backlog, in order`() {
        val stream = InMemoryNotificationStream()
        stream.publish(notification("seller-1", "backlog-1"))
        stream.publish(notification("seller-1", "backlog-2"))

        // Re-entrant publish from inside the listener: the live event must not
        // jump ahead of the backlog still being replayed.
        val recorder = object : Recorder() {
            var injected = false
            override fun onEvent(event: StreamEvent) {
                super.onEvent(event)
                if (!injected) {
                    injected = true
                    stream.publish(notification("seller-1", "live-during-replay"))
                }
            }
        }
        stream.subscribe(setOf("seller-1"), lastEventId = 0L, listener = recorder)

        assertEquals(
            listOf("backlog-1", "backlog-2", "live-during-replay"),
            recorder.subjects(),
        )
    }

    @Test
    fun `cancel stops delivery and releases the subscriber`() {
        val stream = InMemoryNotificationStream()
        val recorder = Recorder()
        val subscription = stream.subscribe(setOf("seller-1"), null, recorder)

        stream.publish(notification("seller-1", "before-cancel"))
        subscription.cancel()
        stream.publish(notification("seller-1", "after-cancel"))

        assertEquals(listOf("before-cancel"), recorder.subjects())
        assertEquals(0, stream.subscriberCount())
    }

    @Test
    fun `a listener that throws is dropped without affecting the others`() {
        val stream = InMemoryNotificationStream()
        val healthy = Recorder()
        stream.subscribe(setOf("seller-1"), null, healthy)
        stream.subscribe(setOf("seller-1"), null) { throw IllegalStateException("client gone") }

        stream.publish(notification("seller-1", "first"))
        stream.publish(notification("seller-1", "second"))

        assertEquals(listOf("first", "second"), healthy.subjects())
        assertEquals(1, stream.subscriberCount())
    }

    @Test
    fun `concurrent publishes assign unique sequences`() {
        val stream = InMemoryNotificationStream()
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val seqs = CopyOnWriteArrayList<Long>()

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) { seqs += stream.publish(notification("seller-1")).seq }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "publishers did not finish")

        assertEquals(threads * perThread, seqs.size)
        assertEquals(seqs.size, seqs.toSet().size, "duplicate sequence numbers")
        assertEquals((1L..(threads * perThread)).toSet(), seqs.toSet())
    }
}
