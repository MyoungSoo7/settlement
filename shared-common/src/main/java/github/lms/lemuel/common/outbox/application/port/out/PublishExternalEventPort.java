package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

/**
 * 외부 이벤트 시스템(현재: Spring ApplicationEventPublisher, 향후: Kafka/RabbitMQ)으로
 * outbox 레코드를 발행하는 포트.
 *
 * <p>Kafka 전환 시점에는 이 포트의 Kafka 어댑터 구현체만 추가하면 되므로
 * 도메인 서비스와 스케줄러는 건드릴 필요가 없다.
 */
public interface PublishExternalEventPort {
    /**
     * 이벤트를 외부 버스로 발행. 실패 시 RuntimeException.
     */
    void publish(OutboxEvent event);
}
