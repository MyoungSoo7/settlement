package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 이벤트 발행 폴러.
 *
 * <p>일정 주기로 PENDING outbox 레코드를 배치 조회하여 외부 이벤트 시스템
 * (Spring ApplicationEventPublisher 또는 Kafka)으로 발행하고 PUBLISHED 로 상태 전이시킨다.
 *
 * <p>도메인 트랜잭션과 이벤트 발행을 분리해 "커밋되지 않은 이벤트 유출" 과
 * "커밋된 이벤트 누수" 를 모두 방지한다 — Transactional Outbox 패턴.
 */
@Component
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final LoadOutboxEventPort loadOutboxEventPort;
    private final SaveOutboxEventPort saveOutboxEventPort;
    private final PublishExternalEventPort publishExternalEventPort;
    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingGauge = new AtomicLong(0L);
    private Timer publishTimer;

    public OutboxPublisherScheduler(LoadOutboxEventPort loadOutboxEventPort,
                                    SaveOutboxEventPort saveOutboxEventPort,
                                    PublishExternalEventPort publishExternalEventPort,
                                    MeterRegistry meterRegistry) {
        this.loadOutboxEventPort = loadOutboxEventPort;
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.publishExternalEventPort = publishExternalEventPort;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("outbox.pending.count", pendingGauge, AtomicLong::get)
                .description("outbox_events 테이블의 PENDING 건수")
                .register(meterRegistry);
        this.publishTimer = Timer.builder("outbox.publish.duration")
                .description("단일 outbox 이벤트 외부 발행 처리 시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * PENDING outbox 레코드를 배치 조회 후 하나씩 발행.
     * 각 이벤트 처리를 별도 트랜잭션으로 격리해 부분 실패 시에도 다른 이벤트 발행이 계속된다.
     */
    @Scheduled(fixedDelayString = "${app.outbox.polling-delay-ms:2000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pending = loadOutboxEventPort.findPending(BATCH_SIZE);
        pendingGauge.set(loadOutboxEventPort.countPending());

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox polling: {} pending events to publish", pending.size());

        int published = 0;
        int failed = 0;
        for (OutboxEvent event : pending) {
            try {
                publishSingle(event);
                published++;
            } catch (Exception e) {
                failed++;
                // 개별 이벤트 실패는 swallow 하고 나머지 진행 — 다음 폴링 주기에 재시도
                log.warn("Outbox publish failed for eventId={}, will retry. error={}",
                        event.getEventId(), e.getMessage());
            }
        }

        if (published > 0 || failed > 0) {
            log.info("Outbox batch complete: published={}, failed={}, batchSize={}",
                    published, failed, pending.size());
        }
    }

    /**
     * 개별 이벤트 발행. REQUIRES_NEW 로 자체 트랜잭션을 열어 실패 시 현재 이벤트만 롤백.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void publishSingle(OutboxEvent event) {
        publishTimer.record(() -> {
            try {
                publishExternalEventPort.publish(event);
                event.markPublished();
            } catch (RuntimeException e) {
                event.markFailed(e.getMessage());
                saveOutboxEventPort.save(event);
                throw e;
            }
            saveOutboxEventPort.save(event);
        });
    }
}
