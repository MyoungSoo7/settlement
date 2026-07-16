package github.lms.lemuel.notification.domain

/**
 * Business category of a notification (which domain event it announces) —
 * NOT the delivery channel. Enum so subject/body mapping is exhaustive at
 * compile time.
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
 * Invariants are enforced in `init`, so EVERY construction path — including
 * the data-class `copy()`, which routes through the primary constructor —
 * re-runs validation; there is no way to obtain an invalid instance.
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
        if (recipient.isBlank()) {
            throw NotificationInvariantViolationException("recipient must not be blank")
        }
        if (subject.isBlank()) {
            throw NotificationInvariantViolationException("subject must not be blank")
        }
    }
}
