package github.lms.lemuel.settlement.application.port.in;

import java.util.List;

/**
 * 정산 인덱싱 UseCase (Inbound Port)
 * Elasticsearch 인덱싱을 위한 유스케이스
 */
public interface IndexSettlementUseCase {

    /**
     * 단일 정산 인덱싱
     */
    void indexSettlement(Long settlementId);

    /**
     * 벌크 정산 인덱싱
     */
    void bulkIndexSettlements(List<Long> settlementIds);

    /**
     * 정산 인덱스 삭제
     */
    void deleteSettlementIndex(Long settlementId);
}
