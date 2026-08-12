package github.lms.lemuel.notification.adapter.`in`.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler

/**
 * DLT 배선 검증 — "빈이 뜨는가"가 아니라 "재시도·격리·분류가 실제로 설정되는가"를 본다.
 * (Kafka 브로커 불요.)
 */
class KafkaErrorHandlingConfigTest {

    // 지원 빈은 withBean 으로 직접 등록한다 — Kotlin @Configuration + companion object @Bean 조합은
    // 같은 빈 정의를 두 번 만들어 BeanDefinitionOverrideException 을 낸다.
    // 검사 대상인 KafkaErrorHandlingConfig 만 withUserConfiguration(=register) 으로 올려야
    // @ConditionalOnProperty 가 실제로 평가된다(withBean 은 조건 평가를 건너뛴다).
    private val runner = ApplicationContextRunner()
        .withBean(PropertySourcesPlaceholderConfigurer::class.java)
        .withBean(SimpleMeterRegistry::class.java)
        .withUserConfiguration(KafkaErrorHandlingConfig::class.java)
        .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")

    @Test
    @DisplayName("app.kafka.enabled 미설정이면 배선하지 않는다 — 브로커 없이 부팅하는 기본 경로 보존")
    fun inactiveByDefault() {
        runner.run { context ->
            assertFalse(context.containsBean("kafkaListenerContainerFactory"))
            assertEquals(0, context.getBeanNamesForType(DefaultErrorHandler::class.java).size)
        }
    }

    @Test
    @DisplayName("활성화되면 에러 핸들러가 리스너 컨테이너에 실제로 부착된다")
    fun attachesErrorHandler() {
        runner.withPropertyValues("app.kafka.enabled=true").run { context ->
            val factory = context.getBean(ConcurrentKafkaListenerContainerFactory::class.java)
            val handler = context.getBean(DefaultErrorHandler::class.java)
            assertSame(handler, factory.createContainer("any-topic").commonErrorHandler)
        }
    }

    @Test
    @DisplayName("ack 모드는 RECORD — 리스너에 Acknowledgment 파라미터가 없으므로 수동 모드면 영원히 커밋되지 않는다")
    fun usesRecordAckMode() {
        runner.withPropertyValues("app.kafka.enabled=true").run { context ->
            val factory = context.getBean(ConcurrentKafkaListenerContainerFactory::class.java)
            assertEquals(ContainerProperties.AckMode.RECORD, factory.containerProperties.ackMode)
        }
    }

    @Test
    @DisplayName("독성 메시지 예외는 재시도 없이 즉시 DLT 로 분류된다")
    fun classifiesPoisonExceptionsAsNonRetryable() {
        runner.withPropertyValues("app.kafka.enabled=true").run { context ->
            val handler = context.getBean(DefaultErrorHandler::class.java)
            // removeClassification 은 이전 분류값(Boolean, nullable)을 돌려준다 — false = 재시도 안 함.
            assertEquals(false, handler.removeClassification(JsonProcessingException::class.java))
            assertEquals(false, handler.removeClassification(IllegalArgumentException::class.java))
            assertEquals(false, handler.removeClassification(IllegalStateException::class.java))
        }
    }

    @Test
    @DisplayName("파싱 불가 예외는 IAE 계열이라 즉시-DLT 분류에 자동으로 포함된다")
    fun unparseablePayloadIsNonRetryable() {
        val exception = UnparseableEventPayloadException("t", "k", RuntimeException("bad"))
        // 분류는 타입 계층을 따르므로 IAE 등록만으로 이 예외까지 덮인다.
        assert(exception is IllegalArgumentException)
    }

    @Test
    @DisplayName("DLT·재시도 카운터가 형제 서비스와 같은 이름 규칙으로 등록된다")
    fun registersMetrics() {
        runner.withPropertyValues("app.kafka.enabled=true").run { context ->
            context.getBean(DefaultErrorHandler::class.java)
            val registry = context.getBean(MeterRegistry::class.java)
            assertNotNull(registry.find(KafkaErrorHandlingConfig.DLT_PUBLISHED_METRIC).counter())
            assertNotNull(registry.find(KafkaErrorHandlingConfig.RETRY_METRIC).counter())
        }
    }

    @Test
    @DisplayName("DLT 목적지는 <원본>.DLT 이고 이미 .DLT 면 다시 붙이지 않는다")
    fun dltTopicNaming() {
        assertEquals("lemuel.payment.captured.DLT", KafkaErrorHandlingConfig.dltTopicOf("lemuel.payment.captured"))
        assertEquals("lemuel.payment.captured.DLT", KafkaErrorHandlingConfig.dltTopicOf("lemuel.payment.captured.DLT"))
    }

    @Test
    @DisplayName("동시성은 설정값을 따른다 — 만들어진 컨테이너에 실제로 반영되는지 본다")
    fun honoursConcurrency() {
        runner.withPropertyValues("app.kafka.enabled=true", "app.kafka.consumer.concurrency=3").run { context ->
            val factory = context.getBean(ConcurrentKafkaListenerContainerFactory::class.java)
            val container = factory.createContainer("any-topic") as ConcurrentMessageListenerContainer<*, *>
            assertEquals(3, container.concurrency)
        }
    }
}
