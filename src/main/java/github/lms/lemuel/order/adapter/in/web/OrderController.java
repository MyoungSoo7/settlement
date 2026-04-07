package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.adapter.in.web.request.CreateOrderRequest;
import github.lms.lemuel.order.adapter.in.web.response.OrderResponse;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Order API Controller
 */
@Tag(name = "Order", description = "주문 생성/조회/상태 변경 API")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ChangeOrderStatusUseCase changeOrderStatusUseCase;

    @Operation(summary = "주문 생성", description = "사용자/상품/금액을 기반으로 주문을 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Order order = createOrderUseCase.createOrder(
                new CreateOrderUseCase.CreateOrderCommand(request.getUserId(), request.getProductId(), request.getAmount())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @Operation(summary = "주문 단건 조회", description = "주문 ID로 주문을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "주문 ID", required = true) @PathVariable Long id) {
        Order order = getOrderUseCase.getOrderById(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @Operation(summary = "사용자별 주문 목록 조회", description = "지정한 사용자의 모든 주문을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getUserOrders(
            @Parameter(description = "사용자 ID", required = true) @PathVariable Long userId) {
        List<OrderResponse> orders = getOrderUseCase.getOrdersByUserId(userId)
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @Operation(summary = "전체 주문 조회 (관리자)", description = "시스템의 모든 주문을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/admin/all")
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<OrderResponse> orders = getOrderUseCase.getAllOrders()
                .stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(orders);
    }

    @Operation(summary = "주문 취소", description = "주문 상태를 CANCELED로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 성공"),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "취소할 수 없는 상태")
    })
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @Parameter(description = "주문 ID", required = true) @PathVariable Long id) {
        Order order = changeOrderStatusUseCase.cancelOrder(id);
        return ResponseEntity.ok(OrderResponse.from(order));
    }
}
