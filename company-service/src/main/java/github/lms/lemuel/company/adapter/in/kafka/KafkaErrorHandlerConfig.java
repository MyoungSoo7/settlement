package github.lms.lemuel.company.adapter.in.kafka;

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
 * company 서비스 Kafka 컨슈머 에러 핸들링 설정(loan/settlement/investment {@code KafkaErrorHandlerConfig} 동형 배선).
 *
 * <p>배경(감사 지적): 기존엔 Spring Kafka 기본 에러핸들러({@code FixedBackOff(0, 9)})만 있어
 * 재시도 소진 후 메시지를 조용히 skip = 사실상 유실했다. user.registered 는 셀러 링크 대상 목록
 * 적재의 유일한 트리거이므로 유실 시 admin 수동 재링크 전까지 셀러가 문서함 대상에서 누락된다.
 *
 * <p>핵심 동작:
 * <ol>
 *   <li>일시적 예외(DB lock timeout, IO error) → {@code FixedBackOff(2s, 3회)} 재시도</li>
 *   <li>독성 메시지(JSON 파싱 실패, 잘못된 도메인 인풋·상태) → 재시도 없이 즉시 DLT</li>
 *   <li>재시도 한계 도달 → {@code <topic>.DLT} 로 복사 후 ack — 같은 파티션 후속 메시지 정상 처리</li>
 * </ol>
 *
 * <p>{@link DeadLetterPublishingRecoverer} 가 원본 헤더(event_id·traceparent)와 {@code kafka_dlt-*}
 * 진단 헤더를 함께 부여해 사후 추적·멱등 replay 를 보장한다. 메트릭 {@code company.kafka.dlt.published}
 * 로 알람 임계를 걸 수 있다. producer 는 {@code acks=all}+idempotence 로 DLT 손실을 막는다.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    /** 재시도 간격(ms). */
    private static final long RETRY_INTERVAL_MS = 2_000L;
    /** 최대 재시도 횟수. 합계 6초(2s × 3) 동안 재시도. */
    private static final long MAX_RETRIES = 3L;

    private final MeterRegistry meterRegistry;
    private final String bootstrapServers;
    /** 리스너 컨테이너 동시성 — 파티션 수 상한. 멱등 2단 방어+sellerId UPSERT 로 파티션 간 병렬 소비가 안전하다. */
    private final int concurrency;

    public KafkaErrorHandlerConfig(MeterRegistry meterRegistry,
                                   @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                   @Value("${app.kafka.consumer.concurrency:3}") int concurrency) {
        this.meterRegistry = meterRegistry;
        this.bootstrapServers = bootstrapServers;
        this.concurrency = concurrency;
    }

    /** DLT publish 전용 ProducerFactory — String value 통과, acks=all+idempotence 로 손실 방지. */
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

    /** 컨슈머 ConsumerFactory — String key/value, 수동 커밋·read_committed. */
    @Bean
    public ConsumerFactory<String, String> companyConsumerFactory(
            @Value("${spring.kafka.consumer.group-id:lemuel-company}") String groupId) {
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
     * DLT recoverer — 재시도 소진 시 원본 record 를 {@code <topic>.DLT} 로 복사한다.
     * 원본 {@code event_id}·{@code traceparent} 헤더는 그대로 패스스루 → replay 시 멱등 보장.
     */
    @Bean
    public DeadLetterPublishingRecoverer companyDeadLetterRecoverer(KafkaTemplate<String, String> dltKafkaTemplate) {
        Counter dltPublishedCounter = Counter.builder("company.kafka.dlt.published")
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
     * DefaultErrorHandler — 재시도 + DLT 라우팅.
     *
     * <p>재시도 미적용(즉시 DLT) 예외: {@link JsonProcessingException}(파싱 불가),
     * {@link IllegalArgumentException}(도메인 인풋 검증 실패·잘못된 JSON),
     * {@link IllegalStateException}(도메인 상태 위반).
     */
    @Bean
    public DefaultErrorHandler companyKafkaErrorHandler(DeadLetterPublishingRecoverer companyDeadLetterRecoverer) {
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler(companyDeadLetterRecoverer, backOff);
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                IllegalStateException.class
        );

        Counter retryCounter = Counter.builder("company.kafka.retry")
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
     * 컨슈머 ListenerContainerFactory — autoconfigure 의 동명 빈(@ConditionalOnMissingBean)을 override.
     * {@code @KafkaListener(containerFactory = "kafkaListenerContainerFactory")} 가 이 빈을 참조한다.
     */
    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> companyConsumerFactory,
            DefaultErrorHandler companyKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(companyConsumerFactory);
        factory.setCommonErrorHandler(companyKafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(concurrency);
        log.info("Kafka listener concurrency set to {}", concurrency);
        return factory;
    }
}
