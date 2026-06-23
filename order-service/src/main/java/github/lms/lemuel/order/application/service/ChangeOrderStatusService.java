package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderStatus;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 상태 변경 서비스
 */
@Service
@RequiredArgsConstructor
public class ChangeOrderStatusService implements ChangeOrderStatusUseCase {

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SaveOrderStatusHistoryPort historyPort;

    @Override
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.cancel();

        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, OrderStatus.CREATED.name(), saved.getStatus().name(), "system", "cancelOrder");
        return saved;
    }

    @Override
    @Transactional
    public Order requestCancellation(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.CANCELLATION_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order approveCancellation(Long orderId, String reason, String operator) {
        return changeStatus(orderId, OrderStatus.CANCELLATION_APPROVED, operator, reason);
    }

    @Override
    @Transactional
    public Order requestRefund(Long orderId, String reason, String requestedBy) {
        return changeStatus(orderId, OrderStatus.REFUND_REQUESTED, requestedBy, reason);
    }

    @Override
    @Transactional
    public Order approveRefund(Long orderId, String reason, String operator) {
        return changeStatus(orderId, OrderStatus.REFUND_COMPLETED, operator, reason);
    }

    @Override
    @Transactional
    public Order changeShippingStatus(Long orderId, String status, String reason, String operator) {
        OrderStatus target = OrderStatus.valueOf(status.toUpperCase());
        if (target != OrderStatus.SHIPPING_PENDING
                && target != OrderStatus.IN_TRANSIT
                && target != OrderStatus.DELIVERED) {
            throw new IllegalArgumentException("배송 상태로 변경할 수 없는 값입니다: " + status);
        }
        return changeStatus(orderId, target, operator, reason);
    }

    @Override
    @Transactional
    public Order updateStatus(Long orderId, String status) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus target = OrderStatus.valueOf(status);
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);

        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, previous.name(), saved.getStatus().name(), "system", "updateStatus");
        return saved;
    }

    private Order changeStatus(Long orderId, OrderStatus target, String changedBy, String reason) {
        Order order = loadOrderPort.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderStatus previous = order.getStatus();
        order.transitionTo(target);
        Order saved = saveOrderPort.save(order);
        historyPort.save(orderId, previous.name(), target.name(),
                changedBy == null || changedBy.isBlank() ? "system" : changedBy, reason);
        return saved;
    }
}
