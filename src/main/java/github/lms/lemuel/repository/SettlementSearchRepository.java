package github.lms.lemuel.repository;

import github.lms.lemuel.search.SettlementSearchDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Elasticsearch용 정산 검색 Repository
 */
@Repository
public interface SettlementSearchRepository extends ElasticsearchRepository<SettlementSearchDocument, String> {

    /**
     * 정산 ID로 검색
     */
    List<SettlementSearchDocument> findBySettlementId(Long settlementId);

    /**
     * 정산 상태로 검색
     */
    List<SettlementSearchDocument> findBySettlementStatus(String settlementStatus);

    /**
     * 사용자 ID로 검색
     */
    List<SettlementSearchDocument> findByUserId(Long userId);

    /**
     * 정산 날짜 범위로 검색
     */
    List<SettlementSearchDocument> findBySettlementDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * 환불 존재 여부로 검색
     */
    List<SettlementSearchDocument> findByHasRefund(Boolean hasRefund);
}
