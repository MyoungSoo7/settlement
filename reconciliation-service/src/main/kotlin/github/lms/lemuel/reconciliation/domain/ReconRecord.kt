package github.lms.lemuel.reconciliation.domain

/**
 * A single record participating in reconciliation, keyed by a business key
 * (e.g. paymentKey / orderId). `amountKrw` is the settlement-relevant amount,
 * `status` is a coarse lifecycle status (e.g. PAID, REFUNDED, PENDING).
 *
 * Immutable Kotlin data class — value semantics give us free equals/hashCode
 * and copy(), which the engine relies on for keying and diffing.
 */
data class ReconRecord(
    val businessKey: String,
    val amountKrw: Long,
    val status: String,
)
