package github.lms.lemuel.common.outbox.adapter.out.event;

import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 현재 구현: Spring ApplicationEventPublisher 로 outbox 이벤트를 in-process 전달.
 *
 * <p>Kafka 로 전환할 때는 이 클래스를 유지하지 않고 Kafka 구현체를 @Primary 로 등록하거나
 * @ConditionalOnProperty("app.kafka.enabled", havingValue="true") 로 스위칭한다.
 */
@Component
public class ApplicationEventOutboxPublisher implements PublishExternalEventPort {

    private final ApplicationEventPublisher publisher;

    public ApplicationEventOutboxPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OutboxEvent event) {
        publisher.publishEvent(event);
    }
}
