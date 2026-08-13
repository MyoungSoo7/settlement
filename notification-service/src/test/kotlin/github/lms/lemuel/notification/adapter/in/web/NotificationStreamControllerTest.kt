package github.lms.lemuel.notification.adapter.`in`.web

import github.lms.lemuel.notification.application.NotificationStream
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.Date

/**
 * End-to-end push: a browser opens the SSE stream, an event is dispatched
 * somewhere else in the service, and it lands on that client's connection —
 * and only on the connections entitled to it.
 */
@SpringBootTest(
    properties = ["app.security.jwt.secret=${NotificationStreamControllerTest.SECRET}"],
)
class NotificationStreamControllerTest {

    companion object {
        const val SECRET = "notification-service-test-secret-32bytes+"
    }

    @Autowired lateinit var wac: WebApplicationContext

    @Autowired lateinit var stream: NotificationStream

    private val mockMvc: MockMvc by lazy { MockMvcBuilders.webAppContextSetup(wac).build() }

    private fun token(uid: Long, role: String = "USER"): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject("user$uid@lemuel.co.kr")
            .claim("role", role)
            .claim("uid", uid)
            .issuedAt(Date(now))
            .expiration(Date(now + 60_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.toByteArray()))
            .compact()
    }

    /**
     * SSE writes arrive asynchronously; poll the buffered response briefly.
     * Decoded as UTF-8 the way EventSource does — an SSE stream is UTF-8 by
     * spec, whatever charset the servlet response happens to advertise.
     */
    private fun awaitBody(result: MvcResult, until: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 3_000
        var body = ""
        while (System.currentTimeMillis() < deadline) {
            body = String(result.response.contentAsByteArray, Charsets.UTF_8)
            if (until(body)) return body
            Thread.sleep(25)
        }
        return body
    }

    @Test
    fun `an unauthenticated request never opens a stream`() {
        mockMvc.perform(get("/notifications/stream"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/notifications/stream").param("token", "not-a-jwt"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `an authenticated client receives notifications addressed to it`() {
        val result = mockMvc.perform(get("/notifications/stream").param("token", token(uid = 501)))
            .andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andReturn()

        stream.publish(
            Notification(NotificationType.SETTLEMENT_CONFIRMED, "501", "정산 확정", "본문", "evt-501"),
        )
        // ...and nothing addressed to somebody else.
        stream.publish(
            Notification(NotificationType.SETTLEMENT_CONFIRMED, "999", "남의 정산", "본문", "evt-999"),
        )

        val body = awaitBody(result) { it.contains("정산 확정") }
        result.request.asyncContext?.complete()

        // The space after an SSE field name is optional — accept either form.
        assertTrue(Regex("event: ?notification").containsMatchIn(body), "missing event name in: $body")
        assertTrue(Regex("(?m)^id: ?\\d+$").containsMatchIn(body), "missing resume id in: $body")
        assertTrue(body.contains("정산 확정"), "own notification missing in: $body")
        assertTrue(!body.contains("남의 정산"), "another user's notification leaked into: $body")
    }

    @Test
    fun `reconnecting with Last-Event-ID replays what was missed while offline`() {
        // Published while nobody is connected — the classic reconnect gap.
        stream.publish(
            Notification(NotificationType.PAYMENT_CONFIRMED, "502", "오프라인 중 결제", "본문", "evt-502"),
        )

        val result = mockMvc.perform(
            get("/notifications/stream")
                .param("token", token(uid = 502))
                .header("Last-Event-ID", "0"),
        )
            .andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andReturn()

        val body = awaitBody(result) { it.contains("오프라인 중 결제") }
        result.request.asyncContext?.complete()

        assertTrue(body.contains("오프라인 중 결제"), "missed notification was not replayed: $body")
    }

    @Test
    fun `the stream opens with a reconnect hint so clients back off predictably`() {
        val result = mockMvc.perform(get("/notifications/stream").param("token", token(uid = 503)))
            .andExpect(status().isOk)
            .andReturn()

        val body = awaitBody(result) { it.contains("retry:") }
        result.request.asyncContext?.complete()

        assertTrue(body.contains("retry:"), "no retry hint in: $body")
    }
}
