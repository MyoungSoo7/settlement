package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.math.BigDecimal;

public interface CreateOrderUseCase {

    Order createOrder(CreateOrderCommand command);

    record CreateOrderCommand(
            Long userId,
            Long productId,
            BigDecimal amount
    ) {
        public CreateOrderCommand {
            if (userId == null) {
                throw new OrderInvariantViolationException("User ID cannot be null");
            }
            if (productId == null) {
                throw new OrderInvariantViolationException("Product ID cannot be null");
            }
            if (amount == null) {
                throw new OrderInvariantViolationException("Amount cannot be null");
            }
        }
    }
}
