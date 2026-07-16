package github.lms.lemuel.notification.adapter.`in`.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import github.lms.lemuel.notification.application.NotificationDispatcher
import github.lms.lemuel.notification.domain.NotificationTemplate
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

/**
 * Inbound Kafka adapter. Consumes settlement domain events and maps each to a
 * [github.lms.lemuel.notification.domain.Notification], then dispatches.
 *
 * Gated by `app.kafka.enabled` (default false) so the app boots and the REST/demo
 * path works with NO broker reachable — critical for tests and containers.
 * Kafka health indicator is also disabled (see application.yml).
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = ["enabled"], havingValue = "true")
class DomainEventListener(
    private val dispatcher: NotificationDispatcher,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            "lemuel.settlement.confirmed",
            "lemuel.payment.confirmed",
            "lemuel.investment.executed",
        ],
        groupId = "\${spring.kafka.consumer.group-id:notification-service}",
    )
    fun onEvent(
        @Payload payload: String,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) key: String?,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val fields: Map<String, Any?> = runCatching {
                objectMapper.readValue(payload, Map::class.java) as Map<String, Any?>
            }.getOrElse { emptyMap() }

            // eventId for idempotency: prefer explicit field, then kafka key.
            val eventId = (fields["eventId"] ?: fields["id"] ?: key)?.toString()
            val notification = NotificationTemplate.fromEvent(topic, fields, eventId)

            val result = runBlocking { dispatcher.dispatch(notification) }
            log.info(
                "kafka event topic={} eventId={} deduped={} allSucceeded={}",
                topic, eventId, result.deduped, result.allSucceeded,
            )
        } catch (e: Exception) {
            // Never let a poison message kill the container; log and move on.
            log.error("failed to process kafka event topic={} payload={}", topic, payload, e)
        }
    }
}
