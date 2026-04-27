package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.Settlement;

import java.util.List;

/**
 * 정산 저장 Outbound Port
 */
public interface SaveSettlementPort {

    Settlement save(Settlement settlement);

    List<Settlement> saveAll(List<Settlement> settlements);
}
