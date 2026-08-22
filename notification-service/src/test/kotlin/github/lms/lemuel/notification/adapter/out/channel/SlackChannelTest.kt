package github.lms.lemuel.notification.adapter.out.channel

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Slack channel against a real (loopback) webhook.
 *
 * Two things matter and neither is visible from a mocked HTTP client: the JSON
 * body must survive quotes and newlines in a subject, and a non-2xx answer must
 * become a thrown failure so the dispatcher can count it — a webhook that
 * silently swallows a 500 is a channel that reports success while delivering
 * nothing.
 */
class SlackChannelTest {

    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private var status = 200

    private fun startServer(): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { exchange: HttpExchange ->
            received += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = if (status in 200..299) "ok" else "boom"
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}/hook"
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun notification(subject: String = "정산 확정", body: String = "본문") =
        Notification(NotificationType.GENERIC, "seller-1", subject, body)

    @Test
    fun `the channel is disabled until a webhook url is configured`() {
        // No URL must mean "not wired up", not "wired up and failing every send".
        assertFalse(SlackChannel("").enabled)
        assertFalse(SlackChannel("   ").enabled)
        assertTrue(SlackChannel("https://hooks.example/x").enabled)
        assertEquals("slack", SlackChannel("").name)
    }

    @Test
    fun `a successful post carries the rendered text as valid JSON`() {
        val url = startServer()

        runBlocking { SlackChannel(url).send(notification()) }

        assertEquals(1, received.size)
        val payload = received.first()
        assertTrue(payload.startsWith("""{"text":""""), "unexpected payload: $payload")
        assertTrue(payload.contains("정산 확정"), "subject missing: $payload")
    }

    @Test
    fun `quotes newlines and backslashes are escaped instead of breaking the JSON`() {
        val url = startServer()

        runBlocking {
            SlackChannel(url).send(notification(subject = """a"b\c""", body = "line1\nline2\tend\r"))
        }

        val payload = received.first()
        assertTrue(payload.contains("""\"b\\c"""), "quote/backslash not escaped: $payload")
        assertTrue(payload.contains("""\n"""), "newline not escaped: $payload")
        assertTrue(payload.contains("""\t"""), "tab not escaped: $payload")
        assertTrue(payload.contains("""\r"""), "carriage return not escaped: $payload")
        // A raw newline inside a JSON string is a parse error at the other end.
        assertFalse(payload.contains('\n'), "payload still contains a raw newline: $payload")
    }

    @Test
    fun `a non-2xx answer fails the send instead of being swallowed`() {
        status = 500
        val url = startServer()

        val error = assertThrows<IllegalStateException> {
            runBlocking { SlackChannel(url).send(notification()) }
        }

        assertTrue(error.message!!.contains("500"), "the status must be in the message: ${error.message}")
        assertTrue(error.message!!.contains("boom"), "the body must be in the message: ${error.message}")
    }

    @Test
    fun `an unreachable webhook fails the send`() {
        // Port 1 on loopback refuses immediately — no waiting on a timeout.
        assertThrows<Exception> {
            runBlocking { SlackChannel("http://127.0.0.1:1/hook").send(notification()) }
        }
    }
}
