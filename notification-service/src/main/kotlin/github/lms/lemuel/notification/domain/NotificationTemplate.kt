package github.lms.lemuel.notification.domain

/**
 * Pure, testable formatting. Turns a raw domain event payload into a
 * ready-to-send [Notification]. No side effects.
 */
object NotificationTemplate {

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
        val recipient = (fields["recipient"] ?: fields["userId"] ?: fields["accountId"] ?: "ops@lemuel")
            .toString()
        val subject = when (type) {
            NotificationType.SETTLEMENT_CONFIRMED -> "정산 확정: ${fields["settlementId"] ?: "?"}"
            NotificationType.PAYMENT_CONFIRMED -> "결제 확인: ${fields["paymentId"] ?: "?"}"
            NotificationType.INVESTMENT_EXECUTED -> "투자 체결: ${fields["orderId"] ?: "?"}"
            NotificationType.GENERIC -> "알림: $topicOrType"
        }
        val amount = fields["amount"]?.let { " (금액 $it)" } ?: ""
        val body = "$topicOrType 이벤트가 처리되었습니다$amount."
        return Notification(type, recipient, subject, body, eventId)
    }

    fun classify(topicOrType: String): NotificationType {
        val key = topicOrType.lowercase()
        return when {
            "settlement.confirmed" in key || key == "settlement_confirmed" -> NotificationType.SETTLEMENT_CONFIRMED
            "payment.confirmed" in key || "payment.captured" in key || "payment.refunded" in key
                || key == "payment_confirmed" -> NotificationType.PAYMENT_CONFIRMED
            "investment.executed" in key || key == "investment_executed" -> NotificationType.INVESTMENT_EXECUTED
            else -> NotificationType.GENERIC
        }
    }
}
