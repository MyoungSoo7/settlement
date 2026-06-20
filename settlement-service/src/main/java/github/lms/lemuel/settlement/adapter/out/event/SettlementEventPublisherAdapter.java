package github.lms.lemuel.settlement.adapter.out.event;

import github.lms.lemuel.settlement.adapter.in.event.dto.SettlementIndexEvent;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 정산 이벤트 발행 Adapter (Outbound Adapter)
 * Spring ApplicationEventPublisher를 사용하여 이벤트 발행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementEventPublisherAdapter implements PublishSettlementEventPort {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void publishSettlementCreatedEvent(List<Long> settlementIds) {
        SettlementIndexEvent event = new SettlementIndexEvent(
                settlementIds,
                SettlementIndexEvent.IndexEventType.BATCH_CREATED
        );
        eventPublisher.publishEvent(event);
        log.info("이벤트 발행: BATCH_CREATED, settlementIds.size={}", settlementIds.size());
    }

    @Override
    public void publishSettlementConfirmedEvent(List<Long> settlementIds) {
        SettlementIndexEvent event = new SettlementIndexEvent(
                settlementIds,
                SettlementIndexEvent.IndexEventType.BATCH_CONFIRMED
        );
        eventPublisher.publishEvent(event);
        log.info("이벤트 발행: BATCH_CONFIRMED, settlementIds.size={}", settlementIds.size());
    }
}
