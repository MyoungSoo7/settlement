package github.lms.lemuel.notification.adapter.out.channel

import github.lms.lemuel.notification.application.NotificationChannel
import github.lms.lemuel.notification.domain.Notification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Properties
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

/**
 * Sends email over SMTP. ENABLED only when MAIL_USERNAME + MAIL_PASSWORD are set.
 *
 * Uses jakarta.mail directly (pulled in transitively by spring-boot).
 * NOTE: enabled path is exercised via config; unit tests cover the disabled/log
 * paths and the fan-out contract with a mockk fake, so no live SMTP is required.
 */
@Component
class EmailChannel(
    @Value("\${app.channels.email.host:smtp.gmail.com}") private val host: String,
    @Value("\${app.channels.email.port:587}") private val port: Int,
    @Value("\${app.channels.email.username:}") private val username: String,
    @Value("\${app.channels.email.password:}") private val password: String,
    @Value("\${app.channels.email.from:no-reply@lemuel.co.kr}") private val from: String,
) : NotificationChannel {

    private val log = LoggerFactory.getLogger("notification.channel.email")

    override val name = "email"
    override val enabled: Boolean = username.isNotBlank() && password.isNotBlank()

    override suspend fun send(notification: Notification) {
        withContext(Dispatchers.IO) {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
            }
            val session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() =
                    jakarta.mail.PasswordAuthentication(username, password)
            })
            // NOTE: no `apply {}` here — inside it, `from` would resolve to
            // MimeMessage.getFrom() (Array<Address>) instead of our String field.
            val msg = MimeMessage(session)
            msg.setFrom(InternetAddress(from))
            msg.addRecipient(Message.RecipientType.TO, InternetAddress(notification.recipient))
            msg.subject = notification.subject
            msg.setText(notification.body)
            Transport.send(msg)
            log.debug("email delivered to={}", notification.recipient)
        }
    }
}
