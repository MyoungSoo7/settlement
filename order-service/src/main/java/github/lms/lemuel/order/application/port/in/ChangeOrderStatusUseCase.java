package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

/**
 * 주문 상태 변경 UseCase (Inbound Port)
 */
public interface ChangeOrderStatusUseCase {

    Order cancelOrder(Long orderId);

    Order requestCancellation(Long orderId, String reason, String requestedBy);

    Order approveCancellation(Long orderId, String reason, String operator);

    Order requestRefund(Long orderId, String reason, String requestedBy);

    Order approveRefund(Long orderId, String reason, String operator);

    Order changeShippingStatus(Long orderId, String status, String reason, String operator);

    /**
     * 주문 상태를 임의의 값으로 변경한다 (PAID, REFUNDED 등).
     * 타 바운디드 컨텍스트(예: payment)에서 상태 변경을 요청할 때 사용.
     */
    Order updateStatus(Long orderId, String status);

    /**
     * 미결제 주문 취소 — 입금 기한이 지난 결제(payment 컨텍스트)가 요청한다.
     *
     * <p>결제 전(CREATED) 주문만 취소하고 주문 생성 시 차감한 재고를 원복한다. 이미 결제·취소·환불된
     * 주문이면 <b>아무 것도 바꾸지 않고</b> {@code false} 를 돌려준다 — 잔류 결제 정리가 정상 주문을
     * 건드리는 일은 없어야 한다.
     *
     * @return 실제로 취소했으면 true
     */
    boolean cancelUnpaidOrder(Long orderId, String reason);
}
