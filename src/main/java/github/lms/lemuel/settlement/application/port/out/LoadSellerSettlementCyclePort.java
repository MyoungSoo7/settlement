package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.SettlementCycle;

import java.util.Optional;

/**
 * 결제로부터 판매자의 정산 주기를 해석하는 포트.
 * 매핑 실패 시 {@code Optional.empty()} → 호출부가 {@link SettlementCycle#DAILY} 로 fallback.
 */
public interface LoadSellerSettlementCyclePort {

    Optional<SettlementCycle> findCycleByPaymentId(Long paymentId);
}
