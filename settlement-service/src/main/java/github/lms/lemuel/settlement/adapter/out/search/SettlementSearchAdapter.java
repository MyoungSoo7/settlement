package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Settlement 검색 인덱싱 Adapter (Outbound Adapter)
 * SettlementSearchIndexPort 구현
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = false)
public class SettlementSearchAdapter implements SettlementSearchIndexPort {

    private final ElasticsearchOperations elasticsearchOperations;
    private final SettlementSearchDocumentMapper mapper;

    @Value("${app.search.enabled:false}")
    private boolean searchEnabled;

    @Override
    public void indexSettlement(Settlement settlement) {
        log.info("단일 인덱싱 시작: settlementId={}", settlement.getId());

        SettlementSearchDocument document = mapper.toDocument(settlement);
        elasticsearchOperations.save(document);

        log.info("단일 인덱싱 완료: settlementId={}", settlement.getId());
    }

    @Override
    public void bulkIndexSettlements(List<Settlement> settlements) {
        log.info("벌크 인덱싱 시작: count={}", settlements.size());

        List<SettlementSearchDocument> documents = settlements.stream()
                .map(mapper::toDocument)
                .collect(Collectors.toList());

        // Elasticsearch bulk operation
        documents.forEach(elasticsearchOperations::save);

        log.info("벌크 인덱싱 완료: count={}", documents.size());
    }

    @Override
    public void deleteSettlementIndex(Long settlementId) {
        log.info("인덱스 삭제 시작: settlementId={}", settlementId);

        String id = settlementId.toString();
        elasticsearchOperations.delete(id, SettlementSearchDocument.class);

        log.info("인덱스 삭제 완료: settlementId={}", settlementId);
    }

    @Override
    public boolean isSearchEnabled() {
        return searchEnabled;
    }
}
