package github.lms.lemuel.notification.adapter.out.channel

import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The email channel's two contracts that do not need a live SMTP server:
 * it must stay **disabled** until both credentials are present (a half-configured
 * channel that fails every send is worse than one that is simply off), and a
 * transport failure must surface as a thrown error so the dispatcher counts it.
 */
class EmailChannelTest {

    private fun channel(username: String = "", password: String = "",
                        host: String = "127.0.0.1", port: Int = 1) =
        EmailChannel(host, port, username, password, "no-reply@lemuel.co.kr")

    private fun notification() =
        Notification(NotificationType.GENERIC, "seller@lemuel.co.kr", "정산 확정", "본문")

    @Test
    fun `both credentials are required before the channel counts as enabled`() {
        assertFalse(channel().enabled, "no credentials")
        assertFalse(channel(username = "u").enabled, "username alone is not enough")
        assertFalse(channel(password = "p").enabled, "password alone is not enough")
        assertTrue(channel(username = "u", password = "p").enabled)
        assertEquals("email", channel().name)
    }

    @Test
    fun `an unreachable SMTP host fails the send instead of reporting success`() {
        // Port 1 on loopback refuses immediately, so this exercises the whole
        // build-message path and then the transport failure — without a real MTA.
        assertThrows<Exception> {
            runBlocking { channel(username = "u", password = "p").send(notification()) }
        }
    }

    @Test
    fun `a malformed recipient address is rejected before anything is sent`() {
        val broken = Notification(NotificationType.GENERIC, "not an address", "s", "b")

        assertThrows<Exception> {
            runBlocking { channel(username = "u", password = "p").send(broken) }
        }
    }
}
