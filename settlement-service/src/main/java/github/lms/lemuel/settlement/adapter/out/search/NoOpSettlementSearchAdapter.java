package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * No-Op Settlement Search Adapter
 * 검색 기능이 비활성화된 경우 사용되는 구현체
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpSettlementSearchAdapter implements SettlementSearchIndexPort {

    @Override
    public void indexSettlement(Settlement settlement) {
        log.debug("Search is disabled, skipping indexSettlement: {}", settlement.getId());
    }

    @Override
    public void bulkIndexSettlements(List<Settlement> settlements) {
        log.debug("Search is disabled, skipping bulkIndexSettlements: count={}", settlements.size());
    }

    @Override
    public void deleteSettlementIndex(Long settlementId) {
        log.debug("Search is disabled, skipping deleteSettlementIndex: {}", settlementId);
    }

    @Override
    public boolean isSearchEnabled() {
        return false;
    }
}
