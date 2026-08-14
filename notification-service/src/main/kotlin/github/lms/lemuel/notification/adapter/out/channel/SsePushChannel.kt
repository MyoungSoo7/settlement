package github.lms.lemuel.notification.adapter.out.channel

import github.lms.lemuel.notification.application.NotificationChannel
import github.lms.lemuel.notification.application.NotificationStream
import github.lms.lemuel.notification.domain.Notification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Delivery channel that pushes a notification to the recipient's open browser
 * connections (SSE) — the in-app counterpart of email/slack.
 *
 * Always enabled: unlike SMTP or a webhook there is nothing to configure, and
 * publishing with no listeners is not a failure — the event still enters the
 * retention window, so a client that reconnects with a `Last-Event-ID` still
 * catches it.
 */
@Component
class SsePushChannel(
    private val stream: NotificationStream,
) : NotificationChannel {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name: String = "sse"

    override val enabled: Boolean = true

    override suspend fun send(notification: Notification) {
        val event = stream.publish(notification)
        log.debug("pushed seq={} to recipient={}", event.seq, event.recipient)
    }
}
