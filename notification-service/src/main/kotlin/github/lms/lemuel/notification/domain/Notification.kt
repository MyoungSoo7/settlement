package github.lms.lemuel.notification.domain

/**
 * Channels a notification can be fanned out to.
 * Sealed-ish via enum so mapping is exhaustive at compile time.
 */
enum class NotificationType {
    SETTLEMENT_CONFIRMED,
    PAYMENT_CONFIRMED,
    INVESTMENT_EXECUTED,
    GENERIC,
}

/**
 * Pure domain value object. No framework, no I/O.
 *
 * @param eventId used for idempotent dedupe; null means "not sourced from an event"
 *   (e.g. an ad-hoc REST send) and is therefore never deduped.
 */
data class Notification(
    val type: NotificationType,
    val recipient: String,
    val subject: String,
    val body: String,
    val eventId: String? = null,
) {
    init {
        require(recipient.isNotBlank()) { "recipient must not be blank" }
        require(subject.isNotBlank()) { "subject must not be blank" }
    }
}
