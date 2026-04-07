package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.adapter.in.web.request.CreateMultiItemOrderRequest;
import github.lms.lemuel.order.adapter.in.web.request.CreateOrderRequest;
import github.lms.lemuel.order.adapter.in.web.response.OrderResponse;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Order API Controller
 */
@Validated
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Order order = createOrderUseCase.createOrder(
                new CreateOrderUseCase.CreateOrderCommand(request.getUserId(), request.getProductId(), request.getAmount())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @PostMapping("/multi")
    public ResponseEntity<OrderResponse> createMultiItemOrder(@Valid @RequestBody CreateMultiItemOrderRequest request) {
        List<CreateOrderUseCase.OrderItemCommand> itemCommands = request.items().stream()
                .map(item -> new CreateOrderUseCase.OrderItemCommand(item.productId(), item.quantity()))
                .collect(Collectors.toList());

        CreateOrderUseCase.CreateMultiItemOrderCommand command = new CreateOrderUseCase.CreateMultiItemOrderCommand(
                request.userId(),
                itemCommands,
                request.shippingAddressId(),
                request.couponCode()
        );

        Order order = createOrderUseCase.createMultiItemOrder(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long id) {
        Order order = getOrderUseCase.getOrderById(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getUserOrders(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<OrderResponse> orders = getOrderUseCase.getOrdersByUserId(userId)
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/admin/all")
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<OrderResponse> orders = getOrderUseCase.getAllOrders()
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long id) {
        Order order = changeOrderStatusUseCase.cancelOrder(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }
}
