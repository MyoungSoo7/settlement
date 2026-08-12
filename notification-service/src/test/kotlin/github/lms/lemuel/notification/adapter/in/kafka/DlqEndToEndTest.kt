package github.lms.lemuel.notification.adapter.`in`.kafka

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
 * 를 확인한다. 이 테스트가 없으면 "유실을 막았다"는 주장은 검증되지 않은 채로 남는다.
 */
@SpringBootTest(classes = [DlqEndToEndTest.TestApp::class])
@EmbeddedKafka(
    partitions = 1,
    topics = [DlqEndToEndTest.POISON_TOPIC, DlqEndToEndTest.TRANSIENT_TOPIC],
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

    companion object {
        const val POISON_TOPIC = "notification.test.poison"
        const val TRANSIENT_TOPIC = "notification.test.transient"
    }
}
