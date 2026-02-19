package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.domain.Order;
import github.lms.lemuel.payment.port.out.LoadOrderPort;
import github.lms.lemuel.payment.port.out.LoadOrderPort.OrderInfo;
import github.lms.lemuel.payment.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.repository.OrderRepository;
import org.springframework.stereotype.Component;

/**
 * Adapter for accessing Order aggregate from Payment bounded context
 */
@Component
public class OrderAdapter implements LoadOrderPort, UpdateOrderStatusPort {

    private final OrderRepository orderRepository;

    public OrderAdapter(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public OrderInfo loadOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        
        return new OrderInfo(
            order.getId(),
            order.getAmount(),
            order.getStatus().name()
        );
    }

    @Override
    public void updateOrderStatus(Long orderId, String status) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        
        order.setStatus(Order.OrderStatus.valueOf(status));
        orderRepository.save(order);
    }
}
