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

    /**
     * 정산 확정 대상(해당 일자의 REQUESTED) 을 비관적 락으로 조회한다.
     * 동시 확정 배치/수동 트리거를 직렬화해 이중 확정·이중 원장 적재를 막는다.
     */
    List<Settlement> findConfirmableForUpdate(LocalDate settlementDate);
}
