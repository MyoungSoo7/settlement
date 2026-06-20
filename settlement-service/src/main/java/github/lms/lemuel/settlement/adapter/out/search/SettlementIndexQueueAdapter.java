package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.adapter.out.persistence.SettlementIndexQueueJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementIndexQueueRepository;
import github.lms.lemuel.settlement.application.port.out.EnqueueFailedIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 인덱싱 실패 시 재시도 큐 추가 Adapter (Outbound Adapter)
 * settlement_index_queue 테이블에 재시도 작업을 저장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = false)
public class SettlementIndexQueueAdapter implements EnqueueFailedIndexPort {

    private final SpringDataSettlementIndexQueueRepository queueRepository;

    @Override
    public void enqueueForRetry(Long settlementId, String operation) {
        log.info("재시도 큐 추가: settlementId={}, operation={}", settlementId, operation);

        var entity = new SettlementIndexQueueJpaEntity(settlementId, operation);
        queueRepository.save(entity);

        log.info("재시도 큐 저장 완료: settlementId={}, nextRetryAt={}", settlementId, entity.getNextRetryAt());
    }
}
