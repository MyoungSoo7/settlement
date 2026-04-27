package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 정산 조회 Outbound Port
 */
public interface LoadSettlementPort {

    Optional<Settlement> findById(Long settlementId);

    Optional<Settlement> findByPaymentId(Long paymentId);

    List<Settlement> findBySettlementDate(LocalDate settlementDate);

    List<Settlement> findBySettlementDateAndStatus(LocalDate settlementDate, SettlementStatus status);
}
