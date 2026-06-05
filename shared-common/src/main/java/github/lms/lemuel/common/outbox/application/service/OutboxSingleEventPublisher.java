package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단일 outbox 이벤트 발행 트랜잭션 경계.
 */
@Service
public class OutboxSingleEventPublisher {

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final PublishExternalEventPort publishExternalEventPort;
    private final PublishDlqEventPort publishDlqEventPort;
    private final Timer publishTimer;
    private final Counter dlqCounter;

    public OutboxSingleEventPublisher(SaveOutboxEventPort saveOutboxEventPort,
                                      PublishExternalEventPort publishExternalEventPort,
                                      PublishDlqEventPort publishDlqEventPort,
                                      MeterRegistry meterRegistry) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.publishExternalEventPort = publishExternalEventPort;
        this.publishDlqEventPort = publishDlqEventPort;
        this.publishTimer = Timer.builder("outbox.publish.duration")
                .description("단일 outbox 이벤트 외부 발행 처리 시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.dlqCounter = Counter.builder("outbox.dlq.published")
                .description("DLQ 로 발행된 누적 이벤트 수 (재시도 한계 초과)")
                .register(meterRegistry);
    }

    /**
     * 개별 이벤트 발행. REQUIRES_NEW 로 자체 트랜잭션을 열어 실패 시 현재 이벤트만 롤백.
     *
     * <p>재시도 한계 (10회) 초과로 FAILED 전이된 시점에 DLQ 토픽으로도 발행한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OutboxEvent event) {
        publishTimer.record(() -> {
            try {
                publishExternalEventPort.publish(event);
                event.markPublished();
            } catch (RuntimeException e) {
                event.markFailed(e.getMessage());
                if (event.isFailed()) {
                    publishDlqEventPort.publishToDlq(event);
                    dlqCounter.increment();
                }
                saveOutboxEventPort.save(event);
                throw e;
            }
            saveOutboxEventPort.save(event);
        });
    }
}
