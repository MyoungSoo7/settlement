package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.IndexSettlementUseCase;
import github.lms.lemuel.settlement.application.port.out.EnqueueFailedIndexPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 정산 인덱싱 서비스
 * Elasticsearch/Search 기술에 의존하지 않는 순수 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexSettlementService implements IndexSettlementUseCase {

    private final LoadSettlementPort loadSettlementPort;
    private final SettlementSearchIndexPort settlementSearchIndexPort;
    private final EnqueueFailedIndexPort enqueueFailedIndexPort;

    @Override
    public void indexSettlement(Long settlementId) {
        if (!settlementSearchIndexPort.isSearchEnabled()) {
            log.debug("Search indexing is disabled, skipping settlement: {}", settlementId);
            return;
        }

        log.info("인덱싱 시작: settlementId={}", settlementId);

        try {
            Settlement settlement = loadSettlementPort.findById(settlementId)
                    .orElseThrow(() -> new IllegalArgumentException("Settlement not found: " + settlementId));

            settlementSearchIndexPort.indexSettlement(settlement);

            log.info("인덱싱 성공: settlementId={}", settlementId);
        } catch (Exception e) {
            log.error("인덱싱 실패: settlementId={}, error={}", settlementId, e.getMessage(), e);
            // 실패 시 재시도 큐에 추가
            enqueueFailedIndexPort.enqueueForRetry(settlementId, "INDEX");
            throw e;
        }
    }

    @Override
    public void bulkIndexSettlements(List<Long> settlementIds) {
        if (!settlementSearchIndexPort.isSearchEnabled()) {
            log.debug("Search indexing is disabled, skipping bulk indexing");
            return;
        }

        log.info("벌크 인덱싱 시작: count={}", settlementIds.size());

        try {
            // 정산 조회
            List<Settlement> settlements = settlementIds.stream()
                    .map(id -> loadSettlementPort.findById(id).orElse(null))
                    .filter(settlement -> settlement != null)
                    .collect(Collectors.toList());

            if (settlements.isEmpty()) {
                log.warn("인덱싱할 정산이 없음");
                return;
            }

            // 벌크 인덱싱
            settlementSearchIndexPort.bulkIndexSettlements(settlements);

            log.info("벌크 인덱싱 성공: count={}", settlements.size());
        } catch (Exception e) {
            log.error("벌크 인덱싱 실패: count={}, error={}", settlementIds.size(), e.getMessage(), e);
            // 실패 시 개별적으로 재시도 큐에 추가
            settlementIds.forEach(id -> enqueueFailedIndexPort.enqueueForRetry(id, "INDEX"));
            throw e;
        }
    }

    @Override
    public void deleteSettlementIndex(Long settlementId) {
        if (!settlementSearchIndexPort.isSearchEnabled()) {
            log.debug("Search indexing is disabled, skipping delete");
            return;
        }

        log.info("인덱스 삭제 시작: settlementId={}", settlementId);

        try {
            settlementSearchIndexPort.deleteSettlementIndex(settlementId);
            log.info("인덱스 삭제 성공: settlementId={}", settlementId);
        } catch (Exception e) {
            log.error("인덱스 삭제 실패: settlementId={}, error={}", settlementId, e.getMessage(), e);
            throw e;
        }
    }
}
