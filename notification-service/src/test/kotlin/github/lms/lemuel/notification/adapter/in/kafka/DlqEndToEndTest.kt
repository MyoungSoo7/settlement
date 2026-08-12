package github.lms.lemuel.notification.adapter.`in`.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import github.lms.lemuel.notification.application.ChannelResult
import github.lms.lemuel.notification.application.DispatchNotificationUseCase
import github.lms.lemuel.notification.application.DispatchResult
import github.lms.lemuel.notification.domain.Notification
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실 브로커(EmbeddedKafka)로 DLT 격리를 증명한다.
 *
 * 설정 단위 테스트는 "배선이 맞다"까지만 말할 수 있다. 여기서는 실제로
 *  - 독성 메시지(즉시-DLT 분류)가 **재시도 없이** `<topic>.DLT` 로 가고
 *  - 일시적 예외가 **정확히 4회 전달**(최초 1 + 재시도 3) 후 DLT 로 가는지
 *  - **운영 리스너**([DomainEventListener])에서 활성 채널이 전멸했을 때 그 알림이 DLT 에 남는지
 * 를 확인한다. 이 테스트가 없으면 "유실을 막았다"는 주장은 검증되지 않은 채로 남는다.
 *
 * 앞의 둘은 픽스처 리스너로 에러 핸들러의 분류를 보고, 셋째는 운영 리스너를 그대로 올려
 * "결과 객체로 돌아온 실패 → 예외 승격 → 즉시-DLT 분류 → 브로커 도착"의 사슬을 통째로 본다.
 */
@SpringBootTest(classes = [DlqEndToEndTest.TestApp::class])
@EmbeddedKafka(
    partitions = 1,
    topics = [DlqEndToEndTest.POISON_TOPIC, DlqEndToEndTest.TRANSIENT_TOPIC, DlqEndToEndTest.SETTLEMENT_TOPIC],
    brokerProperties = ["auto.create.topics.enable=true"],
)
@TestPropertySource(
    properties = [
        "app.kafka.enabled=true",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "app.kafka.consumer.concurrency=1",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
class DlqEndToEndTest {

    // dltKafkaTemplate 과 producerTemplate 둘 다 KafkaTemplate<String, String> 이라 타입만으론 모호하다.
    @Autowired
    @Qualifier("producerTemplate")
    private lateinit var producer: KafkaTemplate<String, String>

    @Autowired
    private lateinit var poison: PoisonListener

    @Autowired
    private lateinit var transient: TransientListener

    @Autowired
    private lateinit var failingDispatcher: AllChannelsFailDispatcher

    @Autowired
    private lateinit var brokers: org.springframework.kafka.test.EmbeddedKafkaBroker

    @Test
    @DisplayName("독성 메시지는 재시도 없이 즉시 DLT 로 격리된다")
    fun poisonGoesStraightToDlt() {
        producer.send(POISON_TOPIC, "k1", """{"bad":true}""").get()

        val record = awaitDlt("$POISON_TOPIC.DLT")

        assertEquals("""{"bad":true}""", record)
        // 최초 1회만 전달 — 재시도가 붙었다면 이 값이 커진다.
        assertEquals(1, poison.calls.get())
    }

    @Test
    @DisplayName("일시적 예외는 3회 재시도 후 DLT 로 간다 — 조용히 사라지지 않는다")
    fun transientRetriesThenDlt() {
        producer.send(TRANSIENT_TOPIC, "k2", "payload").get()

        val record = awaitDlt("$TRANSIENT_TOPIC.DLT")

        assertEquals("payload", record)
        assertEquals(4, transient.calls.get(), "최초 1회 + 재시도 3회여야 한다")
    }

    @Test
    @DisplayName("활성 채널이 전부 실패하면 실제 브로커에서도 <topic>.DLT 로 격리된다 — 재시도 없이 1회")
    fun allChannelsFailedGoesToDlt() {
        val payload = """{"sellerId":"S1","settlementId":"SET-1"}"""

        producer.send(SETTLEMENT_TOPIC, "SET-1", payload).get()

        val record = awaitDlt("$SETTLEMENT_TOPIC.DLT")

        assertEquals(payload, record)
        // NotificationDispatchFailedException 은 IllegalStateException 계열 = 즉시-DLT 분류.
        // 채널이 이미 자체 재시도를 소진했으므로 Kafka 재시도가 붙으면 안 된다.
        assertEquals(1, failingDispatcher.calls.get(), "재시도 없이 최초 1회만 전달되어야 한다")
    }

    private fun awaitDlt(dltTopic: String): String {
        val props = org.springframework.kafka.test.utils.KafkaTestUtils
            .consumerProps(brokers.brokersAsString, "dlt-verifier-$dltTopic", "true")
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"

        DefaultKafkaConsumerFactory<String, String>(props).createConsumer().use { consumer ->
            consumer.subscribe(listOf(dltTopic))
            var value: String? = null
            Awaitility.await().atMost(Duration.ofSeconds(30)).until {
                consumer.poll(Duration.ofMillis(500)).firstOrNull()?.let { value = it.value() }
                value != null
            }
            assertTrue(value != null)
            return value!!
        }
    }

    @SpringBootConfiguration
    @EnableKafka
    @Import(KafkaErrorHandlingConfig::class)
    class TestApp {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun poisonListener() = PoisonListener()

        @Bean
        fun transientListener() = TransientListener()

        /**
         * 픽스처 리스너가 아니라 **운영 리스너 자체**를 올린다. 채널 전멸 → 예외 승격 → 즉시-DLT 분류 →
         * 실제 브로커 왕복까지 한 줄로 이어지는지 확인하기 위해서다(단위 테스트는 throw 까지만 본다).
         */
        @Bean
        fun failingDispatcher() = AllChannelsFailDispatcher()

        @Bean
        fun domainEventListener(dispatcher: AllChannelsFailDispatcher) =
            DomainEventListener(dispatcher, ObjectMapper())

        /** 테스트 producer — 공용 DLT 프로듀서 팩토리를 그대로 재사용한다(토픽이 달라 격리에 무해). */
        @Bean
        fun producerTemplate(dltProducerFactory: ProducerFactory<String, String>) =
            KafkaTemplate(dltProducerFactory)
    }

    /** IllegalArgumentException = 즉시-DLT 분류 대상. */
    class PoisonListener {
        val calls = AtomicInteger()

        @KafkaListener(topics = [POISON_TOPIC], groupId = "poison-listener")
        fun onMessage(value: String) {
            calls.incrementAndGet()
            throw IllegalArgumentException("poison: $value")
        }
    }

    /** 분류에 없는 예외 = 재시도 대상. */
    class TransientListener {
        val calls = AtomicInteger()

        @KafkaListener(topics = [TRANSIENT_TOPIC], groupId = "transient-listener")
        fun onMessage(value: String) {
            calls.incrementAndGet()
            throw RuntimeException("transient: $value")
        }
    }

    /** 모든 채널이 실패한 상태를 흉내낸다 — dispatch 는 던지지 않고 실패 "결과"를 돌려준다(운영과 동일). */
    class AllChannelsFailDispatcher : DispatchNotificationUseCase {
        val calls = AtomicInteger()

        override suspend fun dispatch(notification: Notification): DispatchResult {
            calls.incrementAndGet()
            return DispatchResult(
                deduped = false,
                results = listOf(
                    ChannelResult.Failure("email", attempts = 3, error = "smtp down"),
                    ChannelResult.Failure("sse", attempts = 3, error = "no subscriber"),
                ),
            )
        }
    }

    companion object {
        const val POISON_TOPIC = "notification.test.poison"
        const val TRANSIENT_TOPIC = "notification.test.transient"

        /** 운영 리스너가 실제로 구독하는 토픽 중 하나 — 리스너 애노테이션을 건드리지 않고 그대로 쓴다. */
        const val SETTLEMENT_TOPIC = "lemuel.settlement.confirmed"
    }
}
