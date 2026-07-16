package github.lms.lemuel.notification.application

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Idempotency port. Returns true the FIRST time an id is seen (within TTL),
 * false on redelivery. Pluggable — swap for Redis/DB for durability.
 */
interface DedupeStore {
    /** @return true if [id] was newly recorded (proceed); false if already seen (skip). */
    fun markIfFirst(id: String): Boolean
}

/**
 * In-memory TTL dedupe. Good enough for a single-instance MVP; not durable
 * across restarts and not shared across replicas (see README TODO).
 */
class InMemoryTtlDedupeStore(
    private val ttl: Duration = Duration.ofMinutes(30),
    private val clock: () -> Instant = Instant::now,
) : DedupeStore {

    private val seen = ConcurrentHashMap<String, Instant>()

    override fun markIfFirst(id: String): Boolean {
        val now = clock()
        evictExpired(now)
        // putIfAbsent returns null iff the key was absent → first sight.
        val prior = seen.putIfAbsent(id, now.plus(ttl))
        if (prior != null && prior.isAfter(now)) {
            return false // still-valid prior entry → duplicate
        }
        if (prior != null) {
            // expired entry lingered; refresh and treat as first
            seen[id] = now.plus(ttl)
        }
        return true
    }

    private fun evictExpired(now: Instant) {
        if (seen.size < 1024) return // cheap guard; only sweep when it grows
        seen.entries.removeIf { it.value.isBefore(now) }
    }
}
