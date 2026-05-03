package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.Settlement;

import java.util.List;

/**
 * 정산 검색 인덱싱 Outbound Port
 * Elasticsearch/OpenSearch 의존성을 인터페이스로 분리
 */
public interface SettlementSearchIndexPort {

    /**
     * 단일 정산을 검색 인덱스에 저장
     */
    void indexSettlement(Settlement settlement);

    /**
     * 여러 정산을 벌크로 검색 인덱스에 저장
     */
    void bulkIndexSettlements(List<Settlement> settlements);

    /**
     * 정산 검색 인덱스 삭제
     */
    void deleteSettlementIndex(Long settlementId);

    /**
     * 검색 서비스 활성화 여부
     */
    boolean isSearchEnabled();
}
