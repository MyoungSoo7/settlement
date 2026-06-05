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

    /**
     * 환불 정산 차감 시 동시성 제어용 비관적 락 조회.
     * 트랜잭션 종료 시까지 정산 행을 잠가 동시 환불의 lost update 를 방지한다.
     */
    Optional<Settlement> findByPaymentIdForUpdate(Long paymentId);

    List<Settlement> findBySettlementDate(LocalDate settlementDate);

    List<Settlement> findBySettlementDateAndStatus(LocalDate settlementDate, SettlementStatus status);
}
