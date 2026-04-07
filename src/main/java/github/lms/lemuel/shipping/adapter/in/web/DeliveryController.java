package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.adapter.in.web.dto.*;
import github.lms.lemuel.shipping.application.port.in.DeliveryUseCase;
import github.lms.lemuel.shipping.domain.Delivery;
import github.lms.lemuel.shipping.domain.DeliveryStatus;
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
 * 배송 REST API
 * POST   /api/deliveries                  - 배송 생성
 * GET    /api/deliveries/{id}             - 배송 조회
 * GET    /api/deliveries/order/{orderId}  - 주문별 배송 조회
 * PATCH  /api/deliveries/{id}/ship        - 배송 출발
 * PATCH  /api/deliveries/{id}/status      - 배송 상태 변경
 * GET    /api/deliveries/status/{status}  - 상태별 배송 목록 (관리자)
 */
@Validated
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryUseCase deliveryUseCase;

    @PostMapping
    public ResponseEntity<DeliveryResponse> createDelivery(
            @Valid @RequestBody CreateDeliveryRequest request) {
        Delivery delivery = deliveryUseCase.createDelivery(
                new DeliveryUseCase.CreateDeliveryCommand(
                        request.orderId(),
                        request.addressId(),
                        request.recipientName(),
                        request.phone(),
                        request.address(),
                        request.shippingFee()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(DeliveryResponse.from(delivery));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponse> getDelivery(
            @PathVariable @Positive(message = "배송 ID는 양수여야 합니다") Long id) {
        Delivery delivery = deliveryUseCase.getDelivery(id);
        return ResponseEntity.ok(DeliveryResponse.from(delivery));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<DeliveryResponse> getDeliveryByOrderId(
            @PathVariable @Positive(message = "주문 ID는 양수여야 합니다") Long orderId) {
        Delivery delivery = deliveryUseCase.getDeliveryByOrderId(orderId);
        return ResponseEntity.ok(DeliveryResponse.from(delivery));
    }

    @PatchMapping("/{id}/ship")
    public ResponseEntity<DeliveryResponse> shipDelivery(
            @PathVariable @Positive(message = "배송 ID는 양수여야 합니다") Long id,
            @Valid @RequestBody ShipDeliveryRequest request) {
        Delivery delivery = deliveryUseCase.shipDelivery(id, request.trackingNumber(), request.carrier());
        return ResponseEntity.ok(DeliveryResponse.from(delivery));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<DeliveryResponse> updateStatus(
            @PathVariable @Positive(message = "배송 ID는 양수여야 합니다") Long id,
            @Valid @RequestBody UpdateDeliveryStatusRequest request) {
        DeliveryStatus status = DeliveryStatus.fromString(request.status());
        Delivery delivery = deliveryUseCase.updateStatus(id, status);
        return ResponseEntity.ok(DeliveryResponse.from(delivery));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<DeliveryResponse>> getDeliveriesByStatus(
            @PathVariable String status) {
        DeliveryStatus deliveryStatus = DeliveryStatus.fromString(status);
        List<DeliveryResponse> deliveries = deliveryUseCase.getDeliveriesByStatus(deliveryStatus)
                .stream().map(DeliveryResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(deliveries);
    }
}
