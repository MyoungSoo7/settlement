package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.Settlement;

/**
 * Use Case: 결제로부터 정산 생성
 */
public interface CreateSettlementFromPaymentUseCase {

    /**
     * 결제 완료 시 정산 자동 생성
     * Idempotent: 동일한 paymentId로 여러 번 호출해도 한 번만 생성됨
     *
     * @param paymentId 결제 ID
     * @param orderId 주문 ID
     * @param amount 결제 금액
     * @return 생성된 정산 (이미 존재하면 기존 정산 반환)
     */
    Settlement createSettlementFromPayment(Long paymentId, Long orderId, java.math.BigDecimal amount);
}
