package github.lms.lemuel.settlement.adapter.in.event;

import github.lms.lemuel.settlement.adapter.in.event.dto.SettlementIndexEvent;
import github.lms.lemuel.settlement.application.port.in.IndexSettlementUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 정산 인덱싱 이벤트 리스너 (Inbound Adapter)
 * UseCase만 호출하는 얇은 어댑터
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SettlementIndexEventListener {

    private final IndexSettlementUseCase indexSettlementUseCase;

    /**
     * 정산 인덱싱 이벤트 처리
     * @Async로 비동기 처리
     */
    @Async
    @EventListener
    public void handleSettlementIndexEvent(SettlementIndexEvent event) {
        log.info("이벤트 수신: type={}, count={}", event.getEventType(), event.getSettlementIds().size());

        try {
            switch (event.getEventType()) {
                case BATCH_CREATED:
                case BATCH_CONFIRMED:
                case REFUND_PROCESSED:
                    // 벌크 인덱싱
                    indexSettlementUseCase.bulkIndexSettlements(event.getSettlementIds());
                    break;

                case SINGLE_UPDATED:
                case APPROVED:
                case REJECTED:
                    // 단일 인덱싱
                    for (Long settlementId : event.getSettlementIds()) {
                        indexSettlementUseCase.indexSettlement(settlementId);
                    }
                    break;

                default:
                    log.warn("알 수 없는 이벤트 타입: {}", event.getEventType());
            }

            log.info("이벤트 처리 완료: type={}", event.getEventType());

        } catch (Exception e) {
            log.error("이벤트 처리 실패: type={}, error={}", event.getEventType(), e.getMessage(), e);
            // UseCase 내부에서 이미 재시도 큐에 추가되므로 추가 처리 불필요
        }
    }
}
