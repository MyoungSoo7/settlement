package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

import java.math.BigDecimal;
import java.util.List;

public interface CreateOrderUseCase {

    Order createOrder(CreateOrderCommand command);

    Order createMultiItemOrder(CreateMultiItemOrderCommand command);

    record CreateOrderCommand(
            Long userId,
            Long productId,
            BigDecimal amount
    ) {
        public CreateOrderCommand {
            if (userId == null) {
                throw new IllegalArgumentException("User ID cannot be null");
            }
            if (productId == null) {
                throw new IllegalArgumentException("Product ID cannot be null");
            }
            if (amount == null) {
                throw new IllegalArgumentException("Amount cannot be null");
            }
        }
    }

    record CreateMultiItemOrderCommand(
            Long userId,
            List<OrderItemCommand> items,
            Long shippingAddressId,
            String couponCode
    ) {
        public CreateMultiItemOrderCommand {
            if (userId == null) {
                throw new IllegalArgumentException("User ID cannot be null");
            }
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("Order must contain at least one item");
            }
        }
    }

    record OrderItemCommand(
            Long productId,
            int quantity
    ) {
        public OrderItemCommand {
            if (productId == null) {
                throw new IllegalArgumentException("Product ID cannot be null");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than zero");
            }
        }
    }
}
