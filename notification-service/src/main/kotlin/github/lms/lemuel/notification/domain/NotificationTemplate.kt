package github.lms.lemuel.notification.domain

/**
 * Pure, testable formatting. Turns a raw domain event payload into a
 * ready-to-send [Notification]. No side effects.
 */
object NotificationTemplate {

    /**
     * Recipient of last resort when an event carries no addressable field —
     * routed to the ops mailbox so no event disappears unnoticed (deliberate
     * fail-visible default, not a silent drop).
     */
    private const val OPS_FALLBACK_RECIPIENT = "ops@lemuel"

    /**
     * Render a one-line plain-text representation of a notification,
     * used by the LogChannel and as a fallback body for other channels.
     */
    fun renderPlainText(n: Notification): String =
        "[${n.type}] to=${n.recipient} :: ${n.subject} — ${n.body}"

    /**
     * Build a Notification from a decoded domain event.
     *
     * @param topicOrType logical event name (kafka topic or explicit type string)
     * @param fields decoded event payload
     */
    fun fromEvent(
        topicOrType: String,
        fields: Map<String, Any?>,
        eventId: String?,
    ): Notification {
        val type = classify(topicOrType)
        // Canonical Outbox events (settlement.confirmed, payment.captured/refunded,
        // investment.executed) address the seller via `sellerId` — recipient/userId/
        // accountId are kept for the REST demo path and legacy payloads.
        val recipient = (
            fields["recipient"] ?: fields["sellerId"] ?: fields["userId"] ?: fields["accountId"]
                ?: OPS_FALLBACK_RECIPIENT
            ).toString()
        val subject = when (type) {
            NotificationType.SETTLEMENT_CONFIRMED -> "정산 확정: ${fields["settlementId"] ?: "?"}"
            // Go payment-webhook events carry `paymentKey` instead of `paymentId`.
            NotificationType.PAYMENT_CONFIRMED -> "결제 확인: ${fields["paymentId"] ?: fields["paymentKey"] ?: "?"}"
            NotificationType.INVESTMENT_EXECUTED -> "투자 체결: ${fields["orderId"] ?: "?"}"
            NotificationType.GENERIC -> "알림: $topicOrType"
        }
        // payment.refunded carries refundAmount/refundedAmount, Go payment.confirmed
        // carries totalAmount — none of them a plain `amount`.
        val amountValue = fields["amount"] ?: fields["refundAmount"] ?: fields["refundedAmount"]
            ?: fields["totalAmount"]
        val amount = amountValue?.let { " (금액 $it)" } ?: ""
        val body = "$topicOrType 이벤트가 처리되었습니다$amount."
        return Notification(type, recipient, subject, body, eventId)
    }

    /**
     * Topic/type → category mapping expressed as a declarative rule table
     * (substring OR exact-key match) + a single lookup, so adding a mapping is
     * a data edit, not new branching logic. First matching rule wins; no match
     * (conservative) → GENERIC.
     */
    private val classificationRules: List<ClassificationRule> = listOf(
        ClassificationRule(
            NotificationType.SETTLEMENT_CONFIRMED,
            substrings = listOf("settlement.confirmed"), exactKeys = listOf("settlement_confirmed"),
        ),
        ClassificationRule(
            NotificationType.PAYMENT_CONFIRMED,
            substrings = listOf("payment.confirmed", "payment.captured", "payment.refunded"),
            exactKeys = listOf("payment_confirmed"),
        ),
        ClassificationRule(
            NotificationType.INVESTMENT_EXECUTED,
            substrings = listOf("investment.executed"), exactKeys = listOf("investment_executed"),
        ),
    )

    fun classify(topicOrType: String): NotificationType {
        val key = topicOrType.lowercase()
        return classificationRules.firstOrNull { it.matches(key) }?.type
            ?: NotificationType.GENERIC
    }

    /** One rule of the classification table: type + the keys that select it. */
    private data class ClassificationRule(
        val type: NotificationType,
        val substrings: List<String>,
        val exactKeys: List<String>,
    ) {
        fun matches(key: String): Boolean =
            substrings.any { it in key } || exactKeys.any { it == key }
    }
}
