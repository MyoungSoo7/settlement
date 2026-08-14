package github.lms.lemuel.notification.adapter.`in`.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Default configuration carries NO jwt secret (the service must still boot for
 * the Kafka/REST path). The push stream must then refuse to serve rather than
 * fall back to "trust whoever asks".
 *
 * 시크릿을 빈 값으로 못박는다 — application.yml 이 `${JWT_SECRET:}` 을 읽게 된 뒤로는
 * 셸에 JWT_SECRET 이 떠 있는 개발자(예: .env 를 source 한 경우)에게만 이 테스트가
 * 조용히 다른 걸 검증하게 된다. 주변 환경이 아니라 이 선언이 전제를 정한다.
 */
@SpringBootTest(properties = ["app.security.jwt.secret="])
class NotificationStreamFailClosedTest {

    @Autowired lateinit var wac: WebApplicationContext

    private val mockMvc: MockMvc by lazy { MockMvcBuilders.webAppContextSetup(wac).build() }

    @Test
    fun `the service still boots and serves the rest of the API`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
    }

    @Test
    fun `the stream refuses to serve when no jwt secret is configured`() {
        mockMvc.perform(get("/notifications/stream"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("STREAM_NOT_CONFIGURED"))
    }
}
