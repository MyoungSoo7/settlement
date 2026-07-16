package github.lms.lemuel.notification.adapter.`in`.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Full-context boot test. Verifies the service boots with NO Kafka broker
 * (app.kafka.enabled defaults false, kafka health disabled) and the REST +
 * demo path works end-to-end through the real LogChannel + dispatcher.
 */
@SpringBootTest
class NotificationControllerTest {

    @Autowired lateinit var wac: WebApplicationContext
    @Autowired lateinit var objectMapper: ObjectMapper

    private val mockMvc: MockMvc by lazy { MockMvcBuilders.webAppContextSetup(wac).build() }

    @Test
    fun `health endpoint is UP`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `demo endpoint dispatches through all enabled channels and returns per-channel results`() {
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/notifications/demo"),
        )
            .andDo(print())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deduped").value(false))
            // LogChannel is always enabled → at least one result present and SUCCESS.
            .andExpect(jsonPath("$.results[?(@.channel == 'log')].status").value("SUCCESS"))
            .andExpect(jsonPath("$.allSucceeded").value(true))
    }

    @Test
    fun `send endpoint accepts a body and dispatches`() {
        val body = mapOf(
            "type" to "PAYMENT_CONFIRMED",
            "recipient" to "user@lemuel.co.kr",
            "subject" to "결제 완료",
            "body" to "결제가 확인되었습니다.",
            "eventId" to "web-evt-1",
        )
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/notifications/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.results[?(@.channel == 'log')].status").value("SUCCESS"))
    }
}
