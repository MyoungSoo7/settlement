package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.Settlement;

import java.util.List;

/**
 * 정산 조회 UseCase (Inbound Port)
 */
public interface GetSettlementUseCase {

    Settlement getSettlementById(Long settlementId);

    List<Settlement> getSettlementsByPaymentId(Long paymentId);
}
