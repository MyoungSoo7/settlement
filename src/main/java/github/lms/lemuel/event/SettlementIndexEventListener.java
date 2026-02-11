package github.lms.lemuel.event;

import github.lms.lemuel.service.SettlementIndexQueueService;
import github.lms.lemuel.service.SettlementIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 정산 인덱싱 이벤트 리스너
 * 비동기로 Elasticsearch 동기화 처리
 * 실패 시 큐에 저장하여 재시도
 */
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@Component
public class SettlementIndexEventListener {

    private static final Logger logger = LoggerFactory.getLogger(SettlementIndexEventListener.class);

    private final SettlementIndexService settlementIndexService;
    private final SettlementIndexQueueService queueService;

    public SettlementIndexEventListener(
            SettlementIndexService settlementIndexService,
            SettlementIndexQueueService queueService) {
        this.settlementIndexService = settlementIndexService;
        this.queueService = queueService;
    }

    /**
     * 정산 인덱싱 이벤트 처리
     * @Async 어노테이션으로 비동기 처리
     * 실패 시 재시도 큐에 저장
     */
    @Async
    @EventListener
    public void handleSettlementIndexEvent(SettlementIndexEvent event) {
        logger.info("Received settlement index event: type={}, count={}",
            event.getEventType(), event.getSettlementIds().size());

        try {
            switch (event.getEventType()) {
                case BATCH_CREATED:
                case BATCH_CONFIRMED:
                case REFUND_PROCESSED:
                    // Bulk 인덱싱 시도
                    try {
                        settlementIndexService.bulkIndexSettlements(event.getSettlementIds());
                    } catch (Exception e) {
                        logger.error("Bulk indexing failed, adding to queue: {}", e.getMessage());
                        // 실패 시 각각 큐에 추가
                        for (Long settlementId : event.getSettlementIds()) {
                            queueService.enqueue(settlementId, "INDEX");
                        }
                    }
                    break;

                case SINGLE_UPDATED:
                    // 단일 인덱싱 시도
                    for (Long settlementId : event.getSettlementIds()) {
                        try {
                            settlementIndexService.indexSettlement(settlementId);
                        } catch (Exception e) {
                            logger.error("Single indexing failed for {}, adding to queue: {}",
                                settlementId, e.getMessage());
                            queueService.enqueue(settlementId, "UPDATE");
                        }
                    }
                    break;

                default:
                    logger.warn("Unknown event type: {}", event.getEventType());
            }

            logger.info("Settlement index event processed: type={}", event.getEventType());

        } catch (Exception e) {
            logger.error("Failed to process settlement index event: type={}, error={}",
                event.getEventType(), e.getMessage(), e);
            // 전체 실패 시 모두 큐에 추가
            for (Long settlementId : event.getSettlementIds()) {
                try {
                    queueService.enqueue(settlementId, "INDEX");
                } catch (Exception queueError) {
                    logger.error("Failed to enqueue settlement: {}", settlementId, queueError);
                }
            }
        }
    }
}
