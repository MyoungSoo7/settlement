package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

/**
 * 주문 상태 변경 UseCase (Inbound Port)
 */
public interface ChangeOrderStatusUseCase {

    Order cancelOrder(Long orderId);

    /**
     * 주문 상태를 임의의 값으로 변경한다 (PAID, REFUNDED 등).
     * 타 바운디드 컨텍스트(예: payment)에서 상태 변경을 요청할 때 사용.
     */
    Order updateStatus(Long orderId, String status);
}
