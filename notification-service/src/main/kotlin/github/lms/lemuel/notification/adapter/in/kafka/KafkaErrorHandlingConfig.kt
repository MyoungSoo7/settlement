package github.lms.lemuel.notification.adapter.`in`.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Kafka consumer error handling: bounded retry, then quarantine in `<topic>.DLT`.
 *
 * Why this exists here and not in shared-common: notification-service is a standalone
 * polyglot module (Kotlin / Boot 3.x / JDK 21) that deliberately does NOT depend on the
 * Java platform's `shared-common`, so it cannot reuse `KafkaConsumerErrorHandlingConfig`.
 * This is the same contract expressed in Kotlin — keep the two in sync when either changes.
 *
 * Behaviour:
 *  1. unexpected failures (template rendering, dedupe store, anything not classified below)
 *     → [FixedBackOff] 2s x 3 retries
 *  2. poison messages (unparseable payload, invalid domain input/state) → straight to DLT
 *  3. retries exhausted → copy to `<topic>.DLT`, then commit — the partition keeps moving
 *
 * Note what is deliberately NOT in bucket 1: a failing notification CHANNEL. Channel delivery
 * is retried inside [github.lms.lemuel.notification.application.NotificationDispatcher] (per-channel
 * timeout + 3 attempts with backoff) and never surfaces as a thrown exception, so a Kafka-level
 * retry would only re-enter a dedupe-skipped no-op. When every channel fails the listener raises
 * [NotificationDispatchFailedException], which is classified as poison → quarantined immediately.
 *
 * Ack mode is [ContainerProperties.AckMode.RECORD], NOT `MANUAL_IMMEDIATE`: this service's
 * listener takes no `Acknowledgment` parameter, so a manual mode would never commit and
 * every record would be redelivered forever. RECORD commits after each successfully
 * handled record, which is exactly the semantics the error handler assumes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.kafka", name = ["enabled"], havingValue = "true")
class KafkaErrorHandlingConfig(
    private val meterRegistry: MeterRegistry,
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${app.kafka.consumer.concurrency:1}") private val concurrency: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** DLT publish producer — acks=all + idempotence so the quarantine copy itself cannot be lost. */
    @Bean
    fun dltProducerFactory(): ProducerFactory<String, String> = DefaultKafkaProducerFactory(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.RETRIES_CONFIG to 5,
        ),
    )

    @Bean
    fun dltKafkaTemplate(dltProducerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(dltProducerFactory)

    @Bean
    fun notificationConsumerFactory(
        @Value("\${spring.kafka.consumer.group-id:notification-service}") groupId: String,
    ): ConsumerFactory<String, String> = DefaultKafkaConsumerFactory(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
        ),
    )

    /**
     * Routes a dead record to `<topic>.DLT`, same partition (so key ordering survives replay).
     * Already-`.DLT` topics are not suffixed again — no `.DLT.DLT` growth.
     */
    @Bean
    fun deadLetterRecoverer(
        @Qualifier("dltKafkaTemplate") dltKafkaTemplate: KafkaTemplate<String, String>,
    ): DeadLetterPublishingRecoverer {
        val published = Counter.builder(DLT_PUBLISHED_METRIC)
            .description("Kafka 메시지가 재시도 끝에 DLT 로 publish 된 건수")
            .register(meterRegistry)

        return DeadLetterPublishingRecoverer(dltKafkaTemplate) { record: ConsumerRecord<*, *>, ex: Exception ->
            published.increment()
            log.error(
                "[DLT] publishing record to DLT. topic={}, partition={}, offset={}, exception={}",
                record.topic(), record.partition(), record.offset(), ex.javaClass.simpleName,
            )
            TopicPartition(dltTopicOf(record.topic()), record.partition())
        }
    }

    @Bean
    fun kafkaErrorHandler(deadLetterRecoverer: DeadLetterPublishingRecoverer): DefaultErrorHandler {
        val handler = DefaultErrorHandler(deadLetterRecoverer, FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES))
        // Retrying cannot change the outcome for these — quarantine immediately.
        handler.addNotRetryableExceptions(
            JsonProcessingException::class.java,
            IllegalArgumentException::class.java, // covers UnparseableEventPayloadException + domain invariant violations
            IllegalStateException::class.java,
        )

        val retries = Counter.builder(RETRY_METRIC)
            .description("Kafka 컨슈머 재시도 시도 횟수")
            .register(meterRegistry)
        handler.setRetryListeners({ record, ex, deliveryAttempt ->
            retries.increment()
            log.warn(
                "[Kafka retry] topic={}, partition={}, offset={}, attempt={}, exception={}",
                record.topic(), record.partition(), record.offset(), deliveryAttempt, ex.javaClass.simpleName,
            )
        })
        return handler
    }

    /** Overrides the autoconfigured factory of the same name — `@KafkaListener` picks it up by default. */
    @Bean("kafkaListenerContainerFactory")
    fun kafkaListenerContainerFactory(
        notificationConsumerFactory: ConsumerFactory<String, String>,
        kafkaErrorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(notificationConsumerFactory)
            setCommonErrorHandler(kafkaErrorHandler)
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            setConcurrency(concurrency)
        }

    companion object {
        /** Sibling Java services use `<service>.kafka.*`; keep the same shape for dashboards. */
        const val DLT_PUBLISHED_METRIC = "notification.kafka.dlt.published"
        const val RETRY_METRIC = "notification.kafka.retry"

        private const val RETRY_INTERVAL_MS = 2_000L
        private const val MAX_RETRIES = 3L
        private const val DLT_SUFFIX = ".DLT"

        fun dltTopicOf(topic: String): String =
            if (topic.endsWith(DLT_SUFFIX)) topic else topic + DLT_SUFFIX
    }
}
