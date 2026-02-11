package github.lms.lemuel.service;

import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.repository.SettlementRepository;
import github.lms.lemuel.repository.SettlementSearchRepository;
import github.lms.lemuel.search.SettlementSearchDocument;
import github.lms.lemuel.search.SettlementSearchDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 전체 재인덱싱 서비스
 * 데이터 정합성을 위한 주기적 전체 데이터 재인덱싱
 */
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@Service
public class SettlementReindexService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementReindexService.class);
    private static final int PAGE_SIZE = 100; // 페이지 크기
    private static final int BULK_SIZE = 100; // Bulk insert 크기

    private final SettlementRepository settlementRepository;
    private final SettlementSearchRepository settlementSearchRepository;
    private final SettlementSearchDocumentMapper mapper;

    private volatile boolean isReindexing = false;

    public SettlementReindexService(SettlementRepository settlementRepository,
                                    SettlementSearchRepository settlementSearchRepository,
                                    SettlementSearchDocumentMapper mapper) {
        this.settlementRepository = settlementRepository;
        this.settlementSearchRepository = settlementSearchRepository;
        this.mapper = mapper;
    }

    /**
     * 매주 일요일 새벽 4시에 전체 재인덱싱 실행
     */
    @Scheduled(cron = "0 0 4 * * SUN")
    public void scheduledReindex() {
        logger.info("Scheduled reindex started");
        reindexAll();
    }

    /**
     * 전체 정산 데이터 재인덱싱
     * 수동 호출 가능
     */
    public void reindexAll() {
        if (isReindexing) {
            logger.warn("Reindex already in progress, skipping");
            return;
        }

        isReindexing = true;
        LocalDateTime startTime = LocalDateTime.now();

        try {
            logger.info("Starting full reindex of settlement data");

            // 1. 기존 인덱스 전체 삭제
            logger.info("Deleting existing index...");
            settlementSearchRepository.deleteAll();

            // 2. 전체 Settlement 개수 조회
            long totalCount = settlementRepository.count();
            logger.info("Total settlements to reindex: {}", totalCount);

            // 3. 페이징 처리로 Settlement 조회 및 인덱싱
            int totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);
            int totalIndexed = 0;
            int totalFailed = 0;

            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                try {
                    Pageable pageable = PageRequest.of(pageNumber, PAGE_SIZE);
                    Page<Settlement> settlementPage = settlementRepository.findAll(pageable);

                    List<SettlementSearchDocument> documents = new ArrayList<>();

                    for (Settlement settlement : settlementPage.getContent()) {
                        try {
                            SettlementSearchDocument document = mapper.toDocument(settlement);
                            documents.add(document);
                        } catch (Exception e) {
                            logger.error("Failed to map settlement to document: id={}", settlement.getId(), e);
                            totalFailed++;
                        }
                    }

                    // Bulk insert
                    if (!documents.isEmpty()) {
                        settlementSearchRepository.saveAll(documents);
                        totalIndexed += documents.size();
                        logger.info("Reindexed page {}/{}: {} documents", 
                            pageNumber + 1, totalPages, documents.size());
                    }

                } catch (Exception e) {
                    logger.error("Failed to reindex page {}/{}", pageNumber + 1, totalPages, e);
                    totalFailed += PAGE_SIZE;
                }
            }

            LocalDateTime endTime = LocalDateTime.now();
            long durationSeconds = java.time.Duration.between(startTime, endTime).getSeconds();

            logger.info("Reindex completed: indexed={}, failed={}, total={}, duration={}s",
                totalIndexed, totalFailed, totalCount, durationSeconds);

        } catch (Exception e) {
            logger.error("Reindex failed", e);
            throw e;
        } finally {
            isReindexing = false;
        }
    }

    /**
     * 특정 날짜 범위의 정산 데이터만 재인덱싱
     */
    public void reindexByDateRange(java.time.LocalDate startDate, java.time.LocalDate endDate) {
        logger.info("Starting reindex for date range: {} to {}", startDate, endDate);

        try {
            // 날짜 범위의 Settlement 조회
            List<Settlement> settlements = new ArrayList<>();
            java.time.LocalDate current = startDate;

            while (!current.isAfter(endDate)) {
                settlements.addAll(settlementRepository.findBySettlementDate(current));
                current = current.plusDays(1);
            }

            logger.info("Found {} settlements to reindex", settlements.size());

            // Bulk 인덱싱
            int totalIndexed = 0;
            for (int i = 0; i < settlements.size(); i += BULK_SIZE) {
                int endIndex = Math.min(i + BULK_SIZE, settlements.size());
                List<Settlement> batch = settlements.subList(i, endIndex);

                List<SettlementSearchDocument> documents = new ArrayList<>();
                for (Settlement settlement : batch) {
                    try {
                        documents.add(mapper.toDocument(settlement));
                    } catch (Exception e) {
                        logger.error("Failed to map settlement: id={}", settlement.getId(), e);
                    }
                }

                if (!documents.isEmpty()) {
                    settlementSearchRepository.saveAll(documents);
                    totalIndexed += documents.size();
                }
            }

            logger.info("Reindex by date range completed: indexed={}", totalIndexed);

        } catch (Exception e) {
            logger.error("Reindex by date range failed", e);
            throw e;
        }
    }

    /**
     * 재인덱싱 진행 여부 확인
     */
    public boolean isReindexing() {
        return isReindexing;
    }
}
