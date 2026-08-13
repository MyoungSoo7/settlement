package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 정산 서비스 Kafka 컨슈머 에러 핸들링 설정.
 *
 * <p>핵심 동작:
 * <ol>
 *   <li>일시적 예외 (DB lock timeout, IO error) → ExponentialBackOff(2s × 2 × 3회)</li>
 *   <li>독성 메시지 (JSON 파싱 실패, 잘못된 도메인 인풋) → 재시도 없이 즉시 DLT</li>
 *   <li>재시도 한계 도달 → DLT 로 복사 후 ack — 같은 파티션의 후속 메시지는 정상 처리</li>
 * </ol>
 *
 * <p>설계 의도:
 * <ul>
 *   <li>Spring Kafka 기본은 {@code FixedBackOff(0, 9)} 즉시 9 회 재시도 후 조용히 skip — 메시지 사실상 유실.
 *       이 설정으로 "독성 메시지가 정산 SLA 를 무너뜨리는 것" 을 방어한다.</li>
 *   <li>{@link DeadLetterPublishingRecoverer} 가 원본 헤더(traceparent, event_id) 와 함께
 *       {@code kafka_dlt-*} 헤더(원본 토픽/오프셋/예외 FQCN/스택트레이스)를 자동 부여 → 사후 추적·replay 용이.</li>
 *   <li>메트릭 {@code settlement.kafka.dlt.published.total} 으로 알람 임계 (예: 10/min) 설정 가능.</li>
 * </ul>
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    /** 재시도 간격 (ms). */
    private static final long RETRY_INTERVAL_MS = 2_000L;
    /** 최대 재시도 횟수. 합계 6초 (2s × 3) 동안 재시도. */
    private static final long MAX_RETRIES = 3L;

    private final MeterRegistry meterRegistry;
    private final String bootstrapServers;
    /**
     * 리스너 컨테이너 동시성 — 컨슈머 스레드 수. 각 스레드가 토픽 파티션의 disjoint 부분집합을 맡아
     * 정산 생성을 병렬 처리한다. 유효 상한은 파티션 수(기본 3)이며, 초과분은 idle 이라 토픽 파티션과
     * 함께 올려야 처리량이 는다. 멱등 3단 방어(processed_events PK + settlements.payment_id UNIQUE)로
     * 파티션 간 병렬 처리가 안전하다.
     */
    private final int concurrency;

    public KafkaErrorHandlerConfig(MeterRegistry meterRegistry,
                                    @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                    @Value("${app.kafka.consumer.concurrency:3}") int concurrency) {
        this.meterRegistry = meterRegistry;
        this.bootstrapServers = bootstrapServers;
        this.concurrency = concurrency;
    }

    /**
     * DLT publish 전용 ProducerFactory — 컨슈머 에러 핸들러가 사용.
     *
     * <p>String value 그대로 통과시키므로 StringSerializer 만 있으면 충분.
     * acks=all 로 DLT 손실 방지.
     */
    @Bean
    public ProducerFactory<String, String> dltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> dltKafkaTemplate(ProducerFactory<String, String> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /**
     * 컨슈머 ConsumerFactory — String key/value.
     * spring-boot-autoconfigure 가 만드는 것과 별도로 우리는 명시적으로 둔다 (테스트에서 override 용이).
     */
    @Bean
    public ConsumerFactory<String, String> settlementConsumerFactory(
            @Value("${spring.kafka.consumer.group-id:lemuel-settlement}") String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * DLT recoverer — 재시도 끝나면 원본 record 를 {@code <topic>.DLT} 로 복사한다.
     *
     * <p>{@code DeadLetterPublishingRecoverer} 는 다음 헤더를 자동 추가:
     * <ul>
     *   <li>{@code kafka_dlt-original-topic}</li>
     *   <li>{@code kafka_dlt-original-partition}</li>
     *   <li>{@code kafka_dlt-original-offset}</li>
     *   <li>{@code kafka_dlt-original-timestamp}</li>
     *   <li>{@code kafka_dlt-exception-fqcn}</li>
     *   <li>{@code kafka_dlt-exception-message}</li>
     *   <li>{@code kafka_dlt-exception-stacktrace}</li>
     * </ul>
     * 원본 {@code event_id}, {@code traceparent} 헤더는 그대로 패스스루 → replay 시 멱등 보장.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> dltKafkaTemplate) {
        Counter dltPublishedCounter = Counter.builder("settlement.kafka.dlt.published")
                .description("Kafka 메시지가 재시도 끝에 DLT 로 publish 된 건수")
                .register(meterRegistry);

        return new DeadLetterPublishingRecoverer(dltKafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    dltPublishedCounter.increment();
                    String exClass = ex.getClass().getSimpleName();
                    log.error("[DLT] publishing record to DLT. topic={}, partition={}, offset={}, exception={}",
                            record.topic(), record.partition(), record.offset(), exClass);
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });
    }

    /**
     * DefaultErrorHandler — 재시도 + DLT 라우팅을 담당.
     *
     * <p>재시도 미적용(즉시 DLT) 예외:
     * <ul>
     *   <li>{@link JsonProcessingException} — 페이로드 파싱 불가, 재시도 무의미</li>
     *   <li>{@link IllegalArgumentException} — 도메인 인풋 검증 실패 (음수 금액 등)</li>
     *   <li>{@link IllegalStateException} — 도메인 상태 머신 위반 (이미 종료된 정산 재처리 등)</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 재시도해도 같은 결과인 예외는 즉시 DLT
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                IllegalStateException.class
        );

        Counter retryCounter = Counter.builder("settlement.kafka.retry")
                .description("Kafka 컨슈머 재시도 시도 횟수")
                .register(meterRegistry);
        handler.setRetryListeners((record, ex, deliveryAttempt) -> {
            retryCounter.increment();
            log.warn("[Kafka retry] topic={}, partition={}, offset={}, attempt={}, exception={}",
                    record.topic(), record.partition(), record.offset(),
                    deliveryAttempt, ex.getClass().getSimpleName());
        });

        return handler;
    }

    /**
     * 컨슈머 ListenerContainerFactory — autoconfigure 의 동명 빈을 override.
     *
     * <p>핵심: {@code setCommonErrorHandler} 로 위에서 만든 DLT 핸들러를 등록.
     * {@code @KafkaListener(containerFactory = "kafkaListenerContainerFactory")} 는 이 빈을 참조한다.
     */
    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> settlementConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(settlementConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 파티션 단위 병렬 소비 — concurrency 개의 컨슈머 스레드가 파티션을 나눠 정산 생성을 병렬화.
        factory.setConcurrency(concurrency);
        log.info("Kafka listener concurrency set to {}", concurrency);
        return factory;
    }
}
