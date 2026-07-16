package github.lms.lemuel.notification.application

import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationDispatcherTest {

    private fun channel(nm: String, on: Boolean = true): NotificationChannel =
        mockk(relaxed = true) {
            coEvery { name } returns nm
            coEvery { enabled } returns on
        }

    private val sample = Notification(NotificationType.GENERIC, "x@y.z", "s", "b")

    @Test
    fun `all enabled channels are invoked concurrently and aggregated`() = runTest {
        val a = channel("a")
        val b = channel("b")
        coEvery { a.send(any()) } returns Unit
        coEvery { b.send(any()) } returns Unit

        val dispatcher = NotificationDispatcher(listOf(a, b), InMemoryTtlDedupeStore())
        val result = dispatcher.dispatch(sample)

        assertFalse(result.deduped)
        assertTrue(result.allSucceeded)
        assertEquals(2, result.results.size)
        coVerify(exactly = 1) { a.send(sample) }
        coVerify(exactly = 1) { b.send(sample) }
    }

    @Test
    fun `disabled channels are skipped`() = runTest {
        val on = channel("on", on = true)
        val off = channel("off", on = false)
        coEvery { on.send(any()) } returns Unit

        val result = NotificationDispatcher(listOf(on, off), InMemoryTtlDedupeStore()).dispatch(sample)

        assertEquals(1, result.results.size)
        assertEquals("on", result.results.single().channel)
        coVerify(exactly = 0) { off.send(any()) }
    }

    @Test
    fun `one failing channel does not block the others - failure is isolated and aggregated`() = runTest {
        val good = channel("good")
        val bad = channel("bad")
        coEvery { good.send(any()) } returns Unit
        coEvery { bad.send(any()) } throws RuntimeException("boom")

        // maxAttempts=1 to keep the test fast (no retry backoff delays).
        val dispatcher = NotificationDispatcher(listOf(good, bad), InMemoryTtlDedupeStore(), maxAttempts = 1)
        val result = dispatcher.dispatch(sample)

        val byName = result.results.associateBy { it.channel }
        assertTrue(byName["good"] is ChannelResult.Success)
        val f = byName["bad"]
        assertTrue(f is ChannelResult.Failure)
        assertEquals("boom", (f as ChannelResult.Failure).error)
        assertTrue(result.anySucceeded)
        assertFalse(result.allSucceeded)
    }

    @Test
    fun `a slow channel times out but does not block a fast channel`() = runTest {
        val fast = channel("fast")
        val slow = channel("slow")
        coEvery { fast.send(any()) } returns Unit
        coEvery { slow.send(any()) } coAnswers { delay(10_000) } // exceeds timeout

        val dispatcher = NotificationDispatcher(
            listOf(fast, slow),
            InMemoryTtlDedupeStore(),
            perChannelTimeoutMs = 100,
            maxAttempts = 1,
        )
        val result = dispatcher.dispatch(sample)

        val byName = result.results.associateBy { it.channel }
        assertTrue(byName["fast"] is ChannelResult.Success)
        val f = byName["slow"]
        assertTrue(f is ChannelResult.Failure)
        assertTrue((f as ChannelResult.Failure).error.contains("timeout"))
    }

    @Test
    fun `retry succeeds on second attempt after a transient failure`() = runTest {
        val flaky = channel("flaky")
        var calls = 0
        coEvery { flaky.send(any()) } coAnswers {
            calls++
            if (calls == 1) throw RuntimeException("transient")
        }

        val result = NotificationDispatcher(
            listOf(flaky),
            InMemoryTtlDedupeStore(),
            maxAttempts = 3,
            baseBackoffMs = 1,
        ).dispatch(sample)

        val r = result.results.single()
        assertTrue(r is ChannelResult.Success)
        assertEquals(2, (r as ChannelResult.Success).attempts)
    }

    @Test
    fun `duplicate eventId is dispatched once`() = runTest {
        val ch = channel("c")
        coEvery { ch.send(any()) } returns Unit
        val dispatcher = NotificationDispatcher(listOf(ch), InMemoryTtlDedupeStore())

        val n = sample.copy(eventId = "evt-99")
        val first = dispatcher.dispatch(n)
        val second = dispatcher.dispatch(n)

        assertFalse(first.deduped)
        assertTrue(second.deduped)
        assertTrue(second.results.isEmpty())
        coVerify(exactly = 1) { ch.send(any()) } // only once despite two dispatches
    }
}
