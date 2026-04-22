package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;

/**
 * Kafka 발행 구현체의 자리 표시 (skeleton).
 *
 * <p>현재는 {@code app.kafka.enabled=true} 스위치가 실제로 존재하지 않으며 빈으로
 * 등록되지 않는다. Kafka 도입 시 다음 단계만 추가하면 된다:
 *   1. build.gradle 에 {@code org.springframework.kafka:spring-kafka} 의존성 추가
 *   2. docker-compose.yml 에 Kafka/Redpanda 브로커 구성
 *   3. KafkaTemplate 주입 + @ConditionalOnProperty("app.kafka.enabled") @Component 등록
 *   4. {@link ApplicationEventOutboxPublisher} 를 @ConditionalOnMissingBean 구조로 변경
 *
 * <p>이 구조의 핵심: {@link PublishExternalEventPort} 추상화를 미리 분리해 두어
 * 도메인 서비스와 OutboxPublisherScheduler 는 Kafka 전환 시에도 수정되지 않는다.
 */
public final class KafkaOutboxPublisher implements PublishExternalEventPort {

    // private final KafkaTemplate<String, String> kafkaTemplate;
    // private final String topicPrefix;

    private KafkaOutboxPublisher() {
        // 활성화 전까지 인스턴스 생성 금지
    }

    @Override
    public void publish(OutboxEvent event) {
        // TODO: kafkaTemplate.send(topicPrefix + "." + event.getAggregateType().toLowerCase(),
        //                          event.getAggregateId(),
        //                          event.getPayload());
        throw new UnsupportedOperationException(
                "Kafka 발행은 아직 활성화되지 않았습니다. ApplicationEventOutboxPublisher 를 사용하세요.");
    }
}
