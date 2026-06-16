package github.lms.lemuel.reservation.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * reservation-service Kafka 컨슈머 인프라.
 *
 * <p>shared-common 의 KafkaConfig 는 payment 토픽만 정의하고 컨테이너 팩토리를 제공하지 않으므로
 * (그 팩토리는 settlement-service 소유), reservation-service 는 자체 컨테이너 팩토리 + 에러핸들러 +
 * user 멤버십 토픽/DLT 를 둔다. {@code @KafkaListener(containerFactory="kafkaListenerContainerFactory")}
 * 가 이 빈을 참조한다.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ReservationKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(ReservationKafkaConfig.class);
    private static final long RETRY_INTERVAL_MS = 2_000L;
    private static final long MAX_RETRIES = 3L;

    private final String bootstrapServers;
    private final int concurrency;
    private final String topic;

    public ReservationKafkaConfig(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.consumer.concurrency:3}") int concurrency,
            @Value("${app.kafka.topic.user-membership-changed}") String topic) {
        this.bootstrapServers = bootstrapServers;
        this.concurrency = concurrency;
        this.topic = topic;
    }

    @Bean
    public NewTopic userMembershipChangedTopic() {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic userMembershipChangedDltTopic() {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    @Bean
    public ProducerFactory<String, String> reservationDltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> reservationDltKafkaTemplate(
            ProducerFactory<String, String> reservationDltProducerFactory) {
        return new KafkaTemplate<>(reservationDltProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> reservationConsumerFactory(
            @Value("${spring.kafka.consumer.group-id:lemuel-reservation}") String groupId) {
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

    @Bean
    public DeadLetterPublishingRecoverer reservationDeadLetterRecoverer(
            KafkaTemplate<String, String> reservationDltKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(reservationDltKafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    log.error("[DLT] reservation consumer routing to DLT. topic={}, offset={}, ex={}",
                            record.topic(), record.offset(), ex.getClass().getSimpleName());
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });
    }

    @Bean
    public DefaultErrorHandler reservationKafkaErrorHandler(DeadLetterPublishingRecoverer reservationDeadLetterRecoverer) {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(reservationDeadLetterRecoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                IllegalStateException.class);
        return handler;
    }

    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> reservationConsumerFactory,
            DefaultErrorHandler reservationKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(reservationConsumerFactory);
        factory.setCommonErrorHandler(reservationKafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(concurrency);
        return factory;
    }
}
