package github.lms.lemuel.settlement.adapter.in.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka DLT End-to-End — 실제 (embedded) broker 에 publish 후 DefaultErrorHandler 가
 * 의도대로 라우팅하는지 검증.
 *
 * <p>2 시나리오:
 * <ol>
 *   <li>{@link IllegalArgumentException} → 즉시 DLT (재시도 0 회) — 독성 메시지로 분류</li>
 *   <li>{@link RuntimeException} → FixedBackOff(2s × 3) 재시도 후 DLT (총 4 회 호출)</li>
 * </ol>
 *
 * <p>{@code processed_events} 멱등 의존성을 끊기 위해 테스트는 서비스 빈 대신 토이 리스너만 사용.
 * 검증 대상은 "예외 분류 + 재시도 + DLT 라우팅" 자체다.
 */
@SpringBootTest(
        classes = DlqEndToEndTest.TestApp.class,
        properties = {
                "app.kafka.enabled=true",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                // 컨슈머 1스레드로 고정 — partitions=1 토픽에 concurrency=3(운영 기본)이면
                // 유휴 컨슈머 합류로 리밸런스가 발생해 실패 레코드가 재전달(재시도 카운트 +1)될 수 있다.
                // 재시도 횟수(정확히 4)를 검증하는 E2E 이므로 리밸런스 변수를 제거한다.
                "app.kafka.consumer.concurrency=1"
        }
)
@EmbeddedKafka(
        partitions = 1,
        topics = {
                DlqEndToEndTest.POISON_TOPIC,
                DlqEndToEndTest.POISON_TOPIC + ".DLT",
                DlqEndToEndTest.TRANSIENT_TOPIC,
                DlqEndToEndTest.TRANSIENT_TOPIC + ".DLT"
        }
)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class DlqEndToEndTest {

    static final String POISON_TOPIC = "e2e.poison";
    static final String TRANSIENT_TOPIC = "e2e.transient";

    @Autowired KafkaTemplate<String, String> producerTemplate;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired PoisonListener poisonListener;
    @Autowired TransientListener transientListener;

    @Test
    void illegal_argument_routes_to_DLT_without_retry() {
        // when
        producerTemplate.send(POISON_TOPIC, "{\"x\":1}");

        // then — DLT 에 메시지 1 건 도달
        ConsumerRecord<String, String> dltRecord = pollOneFromDlt(POISON_TOPIC + ".DLT", "verifier-poison");
        assertThat(dltRecord.value()).isEqualTo("{\"x\":1}");

        // 핵심: NotRetryable 분류이므로 리스너는 정확히 1 회만 호출
        assertThat(poisonListener.calls.get())
                .as("IllegalArgumentException 은 즉시 DLT — 재시도 0")
                .isEqualTo(1);

        // 표준 DLT 헤더 — 재처리·사후추적의 핵심
        assertThat(headerValue(dltRecord, "kafka_dlt-original-topic"))
                .isEqualTo(POISON_TOPIC);
        // immediate exception 은 Spring Kafka 의 ListenerExecutionFailedException 래퍼
        assertThat(headerValue(dltRecord, "kafka_dlt-exception-fqcn"))
                .contains("ListenerExecutionFailedException");
        // 실제 원인 예외 — 운영자가 actionable 한 정보
        assertThat(headerValue(dltRecord, "kafka_dlt-exception-cause-fqcn"))
                .contains("IllegalArgumentException");
    }

    @Test
    void transient_runtime_exception_retries_three_times_then_DLT() {
        // when
        producerTemplate.send(TRANSIENT_TOPIC, "{\"y\":2}");

        // then — 재시도 시간(약 6초) 고려해 DLT 폴 타임아웃 넉넉히
        ConsumerRecord<String, String> dltRecord = pollOneFromDlt(
                TRANSIENT_TOPIC + ".DLT", "verifier-transient", Duration.ofSeconds(20));
        assertThat(dltRecord.value()).isEqualTo("{\"y\":2}");

        // 핵심: FixedBackOff(2s, 3) → 1(초기) + 3(재시도) = 4 회 호출
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(transientListener.calls.get())
                        .as("RuntimeException 은 3 회 재시도 후 DLT — 총 4 회 호출")
                        .isEqualTo(4));
    }

    private ConsumerRecord<String, String> pollOneFromDlt(String dltTopic, String groupId) {
        return pollOneFromDlt(dltTopic, groupId, Duration.ofSeconds(10));
    }

    private ConsumerRecord<String, String> pollOneFromDlt(String dltTopic, String groupId, Duration timeout) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(groupId, "true", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(dltTopic));
            return KafkaTestUtils.getSingleRecord(consumer, dltTopic, timeout);
        }
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /**
     * 테스트 전용 부트 컨피그 — 실제 운영 KafkaErrorHandlerConfig 를 임포트해 동일 동작 검증.
     * 자동설정 없이 명시 빈만 사용 → DB·웹·ES 등 무관한 컨텍스트가 뜨지 않아 빠르고 격리됨.
     */
    @SpringBootConfiguration
    @Import(KafkaErrorHandlerConfig.class)
    static class TestApp {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        PoisonListener poisonListener() {
            return new PoisonListener();
        }

        @Bean
        TransientListener transientListener() {
            return new TransientListener();
        }

        /** 테스트 producer — KafkaErrorHandlerConfig 의 dltKafkaTemplate 을 그대로 사용해도 되지만 명시 빈 가독성. */
        @Bean
        KafkaTemplate<String, String> producerTemplate(
                org.springframework.kafka.core.ProducerFactory<String, String> dltProducerFactory) {
            return new KafkaTemplate<>(dltProducerFactory);
        }
    }

    /** 모든 메시지를 독성으로 처리. IllegalArgumentException → 즉시 DLT 분류. */
    static class PoisonListener {
        final AtomicInteger calls = new AtomicInteger();

        @KafkaListener(topics = POISON_TOPIC, groupId = "poison-listener")
        void onMessage(String value) {
            calls.incrementAndGet();
            throw new IllegalArgumentException("poison test: " + value);
        }
    }

    /** 모든 메시지에 transient 실패. 재시도 후 DLT 로 가야 함. */
    static class TransientListener {
        final AtomicInteger calls = new AtomicInteger();

        @KafkaListener(topics = TRANSIENT_TOPIC, groupId = "transient-listener")
        void onMessage(String value) {
            calls.incrementAndGet();
            throw new RuntimeException("transient failure");
        }
    }
}

/*
 * Note: KafkaTemplate 와 ProducerFactory 가 필요하므로 KafkaErrorHandlerConfig 의 dltProducerFactory 빈을
 * 그대로 사용. 실제 운영에서는 동일 인스턴스가 DLT publish 와 우리 테스트 send 를 둘 다 책임지지만,
 * 테스트 격리상 해롭지 않다 (서로 다른 토픽).
 */
