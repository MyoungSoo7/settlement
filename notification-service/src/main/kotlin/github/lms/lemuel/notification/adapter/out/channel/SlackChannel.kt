package github.lms.lemuel.notification.adapter.out.channel

import github.lms.lemuel.notification.application.NotificationChannel
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Posts to a Slack incoming webhook. ENABLED only when SLACK_WEBHOOK_URL is set,
 * so the service runs with no Slack config in tests/containers.
 */
@Component
class SlackChannel(
    @Value("\${app.channels.slack.webhook-url:}") private val webhookUrl: String,
) : NotificationChannel {

    private val log = LoggerFactory.getLogger("notification.channel.slack")
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    override val name = "slack"
    override val enabled: Boolean = webhookUrl.isNotBlank()

    override suspend fun send(notification: Notification) {
        val text = NotificationTemplate.renderPlainText(notification)
        val payload = """{"text":${jsonString(text)}}"""
        // Blocking HTTP call kept off the coroutine's thread pool via Dispatchers.IO.
        withContext(Dispatchers.IO) {
            val req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                throw IllegalStateException("slack webhook returned ${resp.statusCode()}: ${resp.body()}")
            }
            log.debug("slack delivered status={}", resp.statusCode())
        }
    }

    /** Minimal JSON string escaper (no jackson dep needed for one field). */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.append("\"").toString()
    }
}
