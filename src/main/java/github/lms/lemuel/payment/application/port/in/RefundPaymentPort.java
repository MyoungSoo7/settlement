package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.Refund;

public interface RefundPaymentPort {
    /**
     * 부분 또는 전체 환불 처리.
     * 동일 (paymentId, idempotencyKey)로 재호출 시 기존 Refund 반환 (멱등).
     */
    Refund refund(RefundCommand command);
}
