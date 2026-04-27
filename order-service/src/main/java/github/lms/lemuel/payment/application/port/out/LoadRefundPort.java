package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.Refund;

import java.util.List;
import java.util.Optional;

public interface LoadRefundPort {

    Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    /**
     * 결제별 전체 환불 이력 — 관리자·사용자가 환불 내역을 조회할 때 사용.
     * 결과는 requestedAt 내림차순.
     */
    List<Refund> findAllByPaymentId(Long paymentId);
}
