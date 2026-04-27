package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.application.port.out.EnqueueFailedIndexPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-Op Settlement Index Queue Adapter
 * 검색 기능이 비활성화된 경우 사용되는 구현체
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpSettlementIndexQueueAdapter implements EnqueueFailedIndexPort {

    @Override
    public void enqueueForRetry(Long settlementId, String operation) {
        log.debug("Search is disabled, skipping enqueue: settlementId={}, operation={}",
                settlementId, operation);
    }
}
