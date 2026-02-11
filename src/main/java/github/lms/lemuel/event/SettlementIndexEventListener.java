package github.lms.lemuel.event;

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
 */
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@Component
public class SettlementIndexEventListener {

    private static final Logger logger = LoggerFactory.getLogger(SettlementIndexEventListener.class);

    private final SettlementIndexService settlementIndexService;

    public SettlementIndexEventListener(SettlementIndexService settlementIndexService) {
        this.settlementIndexService = settlementIndexService;
    }

    /**
     * 정산 인덱싱 이벤트 처리
     * @Async 어노테이션으로 비동기 처리
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
                    // Bulk 인덱싱
                    settlementIndexService.bulkIndexSettlements(event.getSettlementIds());
                    break;
                    
                case SINGLE_UPDATED:
                    // 단일 인덱싱
                    if (!event.getSettlementIds().isEmpty()) {
                        for (Long settlementId : event.getSettlementIds()) {
                            settlementIndexService.indexSettlement(settlementId);
                        }
                    }
                    break;
                    
                default:
                    logger.warn("Unknown event type: {}", event.getEventType());
            }
            
            logger.info("Settlement index event processed successfully: type={}", event.getEventType());
            
        } catch (Exception e) {
            logger.error("Failed to process settlement index event: type={}", event.getEventType(), e);
            // 실패 시에도 예외를 전파하지 않아 원본 트랜잭션에 영향을 주지 않음
        }
    }
}
