package github.lms.lemuel.notification.adapter.out.channel

import github.lms.lemuel.notification.application.NotificationChannel
import github.lms.lemuel.notification.domain.Notification
import github.lms.lemuel.notification.domain.NotificationTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Always-on default channel. Zero external deps → the service is always demoable.
 */
@Component
class LogChannel : NotificationChannel {
    private val log = LoggerFactory.getLogger("notification.channel.log")

    override val name = "log"
    override val enabled = true

    override suspend fun send(notification: Notification) {
        log.info("NOTIFY {}", NotificationTemplate.renderPlainText(notification))
    }
}
