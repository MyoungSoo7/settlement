package github.lms.lemuel.notification.adapter.out.dedupe

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class DedupeStoreTest {

    @Test
    fun `first sight true, redelivery false`() {
        val store = InMemoryTtlDedupeStore()
        assertTrue(store.markIfFirst("evt-1"))
        assertFalse(store.markIfFirst("evt-1"))
        assertFalse(store.markIfFirst("evt-1"))
    }

    @Test
    fun `distinct ids are independent`() {
        val store = InMemoryTtlDedupeStore()
        assertTrue(store.markIfFirst("a"))
        assertTrue(store.markIfFirst("b"))
    }

    @Test
    fun `entry expires after ttl so it is treated as first again`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val store = InMemoryTtlDedupeStore(ttl = Duration.ofMinutes(10), clock = { now })
        assertTrue(store.markIfFirst("evt"))
        assertFalse(store.markIfFirst("evt"))
        now = now.plus(Duration.ofMinutes(11)) // advance past TTL
        assertTrue(store.markIfFirst("evt"))
    }
}
