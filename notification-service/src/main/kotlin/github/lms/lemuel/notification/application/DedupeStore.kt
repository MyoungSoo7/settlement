package github.lms.lemuel.notification.application

/**
 * Idempotency port. Returns true the FIRST time an id is seen (within TTL),
 * false on redelivery. Pluggable — swap for Redis/DB for durability.
 * Implementations live in the adapter layer (e.g. `adapter/out/dedupe`).
 */
interface DedupeStore {
    /** @return true if [id] was newly recorded (proceed); false if already seen (skip). */
    fun markIfFirst(id: String): Boolean
}
