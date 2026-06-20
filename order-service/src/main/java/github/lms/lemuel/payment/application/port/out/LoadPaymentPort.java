package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.PaymentDomain;

import java.util.Optional;

public interface LoadPaymentPort {
    Optional<PaymentDomain> loadById(Long id);

    /**
     * 환불 동시성 제어용 비관적 락 조회.
     * 트랜잭션 종료 시까지 결제 행을 잠가 동시 환불의 lost update / PG 이중 호출을 방지한다.
     */
    Optional<PaymentDomain> loadByIdForUpdate(Long id);

    Optional<PaymentDomain> loadByOrderId(Long orderId);
}
