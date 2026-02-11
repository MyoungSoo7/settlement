package github.lms.lemuel.service;

import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.domain.SettlementIndexQueue;
import github.lms.lemuel.repository.SettlementIndexQueueRepository;
import github.lms.lemuel.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elasticsearch 색인 재시도 큐 관리 서비스
 */
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementIndexQueueService {

    private final SettlementIndexQueueRepository queueRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementIndexService settlementIndexService;

    /**
     * 색인 작업을 큐에 추가
     */
    @Transactional
    public void enqueue(Long settlementId, String operation) {
        SettlementIndexQueue queue = new SettlementIndexQueue();
        queue.setSettlementId(settlementId);
        queue.setOperation(operation);
        queue.setStatus("PENDING");
        queueRepository.save(queue);

        log.info("Enqueued settlement index operation: settlementId={}, operation={}",
                settlementId, operation);
    }

    /**
     * 큐에 있는 작업 처리 (재시도 로직 포함)
     * 매 1분마다 실행
     */
    @Scheduled(fixedDelay = 60000) // 1분
    @Transactional
    public void processQueue() {
        List<SettlementIndexQueue> pendingItems = queueRepository.findPendingItems(LocalDateTime.now());

        if (pendingItems.isEmpty()) {
            return;
        }

        log.info("Processing {} pending index queue items", pendingItems.size());

        for (SettlementIndexQueue item : pendingItems) {
            processQueueItem(item);
        }
    }

    /**
     * 실패한 작업 재시도
     * 매 5분마다 실행
     */
    @Scheduled(fixedDelay = 300000) // 5분
    @Transactional
    public void retryFailedItems() {
        List<SettlementIndexQueue> failedItems =
            queueRepository.findRetryableFailedItems(LocalDateTime.now());

        if (failedItems.isEmpty()) {
            return;
        }

        log.info("Retrying {} failed index queue items", failedItems.size());

        for (SettlementIndexQueue item : failedItems) {
            item.setStatus("PENDING");
            item.incrementRetry();
            queueRepository.save(item);

            processQueueItem(item);
        }
    }

    private void processQueueItem(SettlementIndexQueue item) {
        try {
            item.setStatus("PROCESSING");
            queueRepository.save(item);

            Settlement settlement = settlementRepository.findById(item.getSettlementId())
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Settlement not found: " + item.getSettlementId()));

            // 작업 수행
            switch (item.getOperation()) {
                case "INDEX":
                case "UPDATE":
                    settlementIndexService.indexSettlement(settlement.getId());
                    break;
                case "DELETE":
                    settlementIndexService.deleteSettlement(settlement.getId());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown operation: " + item.getOperation());
            }

            // 성공 처리
            item.setStatus("SUCCESS");
            item.setProcessedAt(LocalDateTime.now());
            queueRepository.save(item);

            log.info("Successfully processed queue item: id={}, settlementId={}, operation={}",
                    item.getId(), item.getSettlementId(), item.getOperation());

        } catch (Exception e) {
            log.error("Failed to process queue item: id={}, settlementId={}, operation={}, error={}",
                    item.getId(), item.getSettlementId(), item.getOperation(), e.getMessage(), e);

            item.setErrorMessage(e.getMessage());

            if (item.canRetry()) {
                item.setStatus("FAILED");
                item.incrementRetry();
            } else {
                item.setStatus("FAILED");
                log.error("Max retries reached for queue item: id={}, settlementId={}",
                        item.getId(), item.getSettlementId());
            }

            queueRepository.save(item);
        }
    }

    /**
     * 성공한 큐 아이템 정리 (30일 이상 된 것)
     * 매일 새벽 4시 실행
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupOldSuccessItems() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
        List<SettlementIndexQueue> oldItems = queueRepository
                .findAll()
                .stream()
                .filter(item -> "SUCCESS".equals(item.getStatus())
                        && item.getProcessedAt() != null
                        && item.getProcessedAt().isBefore(cutoffDate))
                .toList();

        if (!oldItems.isEmpty()) {
            queueRepository.deleteAll(oldItems);
            log.info("Cleaned up {} old success queue items", oldItems.size());
        }
    }
}
