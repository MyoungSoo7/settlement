package github.lms.lemuel.notification.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationTemplateTest {

    @Test
    fun `classifies topics into notification types`() {
        assertEquals(NotificationType.SETTLEMENT_CONFIRMED, NotificationTemplate.classify("lemuel.settlement.confirmed"))
        assertEquals(NotificationType.PAYMENT_CONFIRMED, NotificationTemplate.classify("lemuel.payment.confirmed"))
        assertEquals(NotificationType.INVESTMENT_EXECUTED, NotificationTemplate.classify("lemuel.investment.executed"))
        assertEquals(NotificationType.GENERIC, NotificationTemplate.classify("some.random.topic"))
    }

    @Test
    fun `builds notification from settlement event fields`() {
        val n = NotificationTemplate.fromEvent(
            topicOrType = "lemuel.settlement.confirmed",
            fields = mapOf("settlementId" to "STL-42", "recipient" to "a@b.c", "amount" to 1000),
            eventId = "evt-1",
        )
        assertEquals(NotificationType.SETTLEMENT_CONFIRMED, n.type)
        assertEquals("a@b.c", n.recipient)
        assertEquals("evt-1", n.eventId)
        assertTrue(n.subject.contains("STL-42"))
        assertTrue(n.body.contains("1000"))
    }

    @Test
    fun `falls back to ops recipient when none present`() {
        val n = NotificationTemplate.fromEvent("lemuel.payment.confirmed", emptyMap(), null)
        assertEquals("ops@lemuel", n.recipient)
    }

    @Test
    fun `renderPlainText is deterministic and includes key fields`() {
        val n = Notification(NotificationType.GENERIC, "x@y.z", "Sub", "Bod")
        assertEquals("[GENERIC] to=x@y.z :: Sub — Bod", NotificationTemplate.renderPlainText(n))
    }
}
