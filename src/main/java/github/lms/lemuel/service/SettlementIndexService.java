package github.lms.lemuel.service;

import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.repository.SettlementRepository;
import github.lms.lemuel.repository.SettlementSearchRepository;
import github.lms.lemuel.search.SettlementSearchDocument;
import github.lms.lemuel.search.SettlementSearchDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 인덱싱 서비스
 * Settlement 데이터를 Elasticsearch에 동기화
 */
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@Service
public class SettlementIndexService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementIndexService.class);
    private static final int BULK_SIZE = 100; // Bulk insert 크기

    private final SettlementRepository settlementRepository;
    private final SettlementSearchRepository settlementSearchRepository;
    private final SettlementSearchDocumentMapper mapper;

    public SettlementIndexService(SettlementRepository settlementRepository,
                                  SettlementSearchRepository settlementSearchRepository,
                                  SettlementSearchDocumentMapper mapper) {
        this.settlementRepository = settlementRepository;
        this.settlementSearchRepository = settlementSearchRepository;
        this.mapper = mapper;
    }

    /**
     * 단일 정산 데이터 인덱싱
     */
    public void indexSettlement(Long settlementId) {
        try {
            Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found: " + settlementId));

            SettlementSearchDocument document = mapper.toDocument(settlement);
            settlementSearchRepository.save(document);

            logger.info("Settlement indexed: id={}", settlementId);
        } catch (Exception e) {
            logger.error("Failed to index settlement: id={}", settlementId, e);
            throw e;
        }
    }

    /**
     * 복수 정산 데이터 Bulk 인덱싱
     */
    public void bulkIndexSettlements(List<Long> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) {
            logger.debug("No settlements to index");
            return;
        }

        logger.info("Starting bulk index for {} settlements", settlementIds.size());

        int totalIndexed = 0;
        int totalFailed = 0;

        // Bulk 크기로 나누어 처리
        for (int i = 0; i < settlementIds.size(); i += BULK_SIZE) {
            int endIndex = Math.min(i + BULK_SIZE, settlementIds.size());
            List<Long> batch = settlementIds.subList(i, endIndex);

            try {
                List<SettlementSearchDocument> documents = new ArrayList<>();

                for (Long settlementId : batch) {
                    try {
                        Settlement settlement = settlementRepository.findById(settlementId).orElse(null);
                        if (settlement != null) {
                            documents.add(mapper.toDocument(settlement));
                        } else {
                            logger.warn("Settlement not found during bulk indexing: id={}", settlementId);
                            totalFailed++;
                        }
                    } catch (Exception e) {
                        logger.error("Failed to map settlement to document: id={}", settlementId, e);
                        totalFailed++;
                    }
                }

                if (!documents.isEmpty()) {
                    settlementSearchRepository.saveAll(documents);
                    totalIndexed += documents.size();
                    logger.debug("Bulk indexed {} documents (batch {}/{})", 
                        documents.size(), (i / BULK_SIZE) + 1, (settlementIds.size() / BULK_SIZE) + 1);
                }

            } catch (Exception e) {
                logger.error("Failed to bulk index batch {}/{}", (i / BULK_SIZE) + 1, 
                    (settlementIds.size() / BULK_SIZE) + 1, e);
                totalFailed += batch.size();
            }
        }

        logger.info("Bulk indexing completed: indexed={}, failed={}, total={}", 
            totalIndexed, totalFailed, settlementIds.size());
    }

    /**
     * 특정 정산 데이터 삭제
     */
    public void deleteSettlement(Long settlementId) {
        try {
            settlementSearchRepository.deleteById(settlementId.toString());
            logger.info("Settlement deleted from index: id={}", settlementId);
        } catch (Exception e) {
            logger.error("Failed to delete settlement from index: id={}", settlementId, e);
        }
    }

    /**
     * 인덱스 전체 삭제
     */
    public void deleteAllIndex() {
        try {
            settlementSearchRepository.deleteAll();
            logger.info("All settlement index deleted");
        } catch (Exception e) {
            logger.error("Failed to delete all settlement index", e);
            throw e;
        }
    }
}
