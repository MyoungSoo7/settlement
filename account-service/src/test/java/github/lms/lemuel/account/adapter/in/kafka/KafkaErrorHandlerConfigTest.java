package github.lms.lemuel.account.adapter.in.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 계정계 컨슈머 실패 처리 배선 (ADR 0017).
 *
 * <p>account 는 소비 전용이라 여기서 메시지를 잃으면 그 회계 이벤트는 어디에도 남지 않는다
 * (재발행 주체가 없다). 그래서 DLT 라우팅·수동 커밋·즉시 DLT 대상 예외를 테스트로 고정한다.
 */
class KafkaErrorHandlerConfigTest {

    private MeterRegistry meterRegistry;
    private KafkaErrorHandlerConfig config;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        config = new KafkaErrorHandlerConfig(meterRegistry, "localhost:9092", 3);
    }

    @Test
    @DisplayName("DLT 프로듀서는 acks=all·멱등 발행")
    void dltProducerIsDurable() {
        ProducerFactory<String, String> factory = config.dltProducerFactory();

        Map<String, Object> props = factory.getConfigurationProperties();
        assertThat(props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(props.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(props.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        assertThat(props.get(ProducerConfig.RETRIES_CONFIG)).isEqualTo(5);
    }

    @Test
    @DisplayName("DLT 템플릿은 위 프로듀서 팩토리를 쓴다")
    void dltTemplateUsesFactory() {
        ProducerFactory<String, String> factory = config.dltProducerFactory();

        KafkaTemplate<String, String> template = config.dltKafkaTemplate(factory);

        assertThat(template.getProducerFactory()).isSameAs(factory);
    }

    @Test
    @DisplayName("컨슈머는 수동 커밋·earliest·read_committed")
    void consumerFactoryIsManualCommit() {
        ConsumerFactory<String, String> factory = config.accountConsumerFactory("lemuel-account");

        Map<String, Object> props = factory.getConfigurationProperties();
        assertThat(props.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("lemuel-account");
        assertThat(props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(props.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG)).isEqualTo("read_committed");
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> stubTemplate() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        return template;
    }

    @Test
    @DisplayName("복구기는 원본 토픽에 .DLT 를 붙인 같은 파티션으로 보내고 DLT 카운터를 올린다")
    void recovererRoutesToDltTopicAndCounts() {
        KafkaTemplate<String, String> template = stubTemplate();
        DeadLetterPublishingRecoverer recoverer = config.accountDeadLetterRecoverer(template);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.settlement.confirmed", 2, 17L, "42", "{}");

        recoverer.accept(record, new IllegalStateException("독성 메시지"));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("lemuel.settlement.confirmed.DLT");
        assertThat(sent.getValue().partition()).isEqualTo(2);
        assertThat(meterRegistry.get("account.kafka.dlt.published").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("파싱 불가·도메인 위반은 재시도 없이 즉시 DLT")
    void nonRetryableExceptionsGoStraightToDlt() {
        KafkaTemplate<String, String> template = stubTemplate();
        DefaultErrorHandler handler = config.accountKafkaErrorHandler(config.accountDeadLetterRecoverer(template));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.settlement.confirmed", 0, 1L, "42", "not-json");

        handler.handleRemaining(new IllegalArgumentException("음수 금액"), List.of(record),
                mock(Consumer.class), mock(MessageListenerContainer.class));

        verify(template).send(any(ProducerRecord.class));
        assertThat(meterRegistry.get("account.kafka.dlt.published").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("리스너 컨테이너는 수동 즉시 ack + 설정된 동시성으로 뜬다")
    void containerFactoryUsesManualAckAndConcurrency() {
        KafkaTemplate<String, String> template = stubTemplate();
        ConsumerFactory<String, String> consumerFactory = config.accountConsumerFactory("lemuel-account");
        DefaultErrorHandler handler = config.accountKafkaErrorHandler(config.accountDeadLetterRecoverer(template));

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(consumerFactory, handler);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(3);
    }

    @Test
    @DisplayName("동시성은 설정값을 그대로 따른다")
    void concurrencyFollowsProperty() {
        KafkaErrorHandlerConfig single = new KafkaErrorHandlerConfig(meterRegistry, "localhost:9092", 1);
        KafkaTemplate<String, String> template = stubTemplate();

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                single.kafkaListenerContainerFactory(
                        single.accountConsumerFactory("lemuel-account"),
                        single.accountKafkaErrorHandler(single.accountDeadLetterRecoverer(template)));

        assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(1);
    }
}
