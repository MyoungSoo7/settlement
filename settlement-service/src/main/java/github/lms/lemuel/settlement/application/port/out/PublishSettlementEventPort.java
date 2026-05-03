package github.lms.lemuel.settlement.application.port.out;

import java.util.List;

/**
 * 정산 이벤트 발행 Outbound Port
 */
public interface PublishSettlementEventPort {

    void publishSettlementCreatedEvent(List<Long> settlementIds);

    void publishSettlementConfirmedEvent(List<Long> settlementIds);
}
