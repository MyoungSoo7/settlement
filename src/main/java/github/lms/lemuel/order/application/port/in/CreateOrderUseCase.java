package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

import java.math.BigDecimal;

public interface CreateOrderUseCase {

    Order createOrder(CreateOrderCommand command);

    record CreateOrderCommand(
            Long userId,
            BigDecimal amount
    ) {
        public CreateOrderCommand {
            if (userId == null) {
                throw new IllegalArgumentException("User ID cannot be null");
            }
            if (amount == null) {
                throw new IllegalArgumentException("Amount cannot be null");
            }
        }
    }
}
