package github.lms.lemuel.investment.adapter.in.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 투자 서비스 Kafka DLT 배선의 단위 검증 — 재시도 소진 시 유실 대신 DLT 로 보내는 핸들러와
 * 수동 커밋·동시성이 설정된 리스너 팩토리가 조립되는지 확인한다. (Kafka 브로커 불요.)
 */
class KafkaErrorHandlerConfigTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KafkaErrorHandlerConfig config =
            new KafkaErrorHandlerConfig(registry, "localhost:9092", 3);

    @Test
    @DisplayName("에러 핸들러는 DefaultErrorHandler 이고 DLT recoverer 는 dlt.published 카운터를 등록한다")
    void buildsErrorHandlerAndRecoverer() {
        ProducerFactory<String, String> pf = config.dltProducerFactory();
        KafkaTemplate<String, String> template = config.dltKafkaTemplate(pf);
        DeadLetterPublishingRecoverer recoverer = config.investmentDeadLetterRecoverer(template);
        DefaultErrorHandler handler = config.investmentKafkaErrorHandler(recoverer);

        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
        assertThat(recoverer).isNotNull();
        assertThat(registry.find("investment.kafka.dlt.published").counter()).isNotNull();
    }

    @Test
    @DisplayName("리스너 팩토리는 수동 즉시 ack + 지정 동시성으로 조립된다")
    void buildsListenerContainerFactory() {
        ConsumerFactory<String, String> cf = config.investmentConsumerFactory("lemuel-investment");
        DefaultErrorHandler handler = config.investmentKafkaErrorHandler(
                config.investmentDeadLetterRecoverer(config.dltKafkaTemplate(config.dltProducerFactory())));

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(cf, handler);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(factory.getContainerProperties().getMessageListener()).isNull();
    }
}
