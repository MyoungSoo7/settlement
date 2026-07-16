package github.lms.lemuel.notification.adapter.`in`.web

import github.lms.lemuel.notification.application.ChannelResult
import github.lms.lemuel.notification.application.DispatchResult
import github.lms.lemuel.notification.application.NotificationDispatcher
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationType
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Inbound REST request. */
data class SendNotificationRequest(
    val type: String? = null,
    val recipient: String,
    val subject: String,
    val body: String,
    val eventId: String? = null,
)

/** Response DTOs — flat, JSON-friendly. */
data class ChannelResultDto(
    val channel: String,
    val status: String,
    val attempts: Int,
    val error: String? = null,
)

data class DispatchResponse(
    val deduped: Boolean,
    val allSucceeded: Boolean,
    val results: List<ChannelResultDto>,
)

@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val dispatcher: NotificationDispatcher,
) {

    @PostMapping("/send")
    fun send(@RequestBody req: SendNotificationRequest): ResponseEntity<DispatchResponse> {
        val notification = Notification(
            type = parseType(req.type),
            recipient = req.recipient,
            subject = req.subject,
            body = req.body,
            eventId = req.eventId,
        )
        // MVC controller bridges into the coroutine world with runBlocking.
        val result = runBlocking { dispatcher.dispatch(notification) }
        return ResponseEntity.ok(result.toResponse())
    }

    @GetMapping("/demo")
    fun demo(): DispatchResponse {
        val sample = Notification(
            type = NotificationType.SETTLEMENT_CONFIRMED,
            recipient = "ops@lemuel.co.kr",
            subject = "데모 정산 확정",
            body = "notification-service 데모 알림 — 모든 활성 채널로 팬아웃합니다.",
            eventId = "demo-${UUID.randomUUID()}",
        )
        return runBlocking { dispatcher.dispatch(sample) }.toResponse()
    }

    private fun parseType(raw: String?): NotificationType =
        raw?.let {
            runCatching { NotificationType.valueOf(it.uppercase()) }.getOrNull()
        } ?: NotificationType.GENERIC

    private fun DispatchResult.toResponse() = DispatchResponse(
        deduped = deduped,
        allSucceeded = allSucceeded,
        results = results.map { r ->
            when (r) {
                is ChannelResult.Success -> ChannelResultDto(r.channel, "SUCCESS", r.attempts)
                is ChannelResult.Failure -> ChannelResultDto(r.channel, "FAILURE", r.attempts, r.error)
            }
        },
    )
}
