package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Shipping", description = "배송 생성 / 출고 / 추적 / 반품")
@RestController
@RequestMapping("/orders/{orderId}/shipment")
public class ShippingController {

    private final ShippingUseCase useCase;
    private final LoadShipmentPort loadPort;

    public ShippingController(ShippingUseCase useCase, LoadShipmentPort loadPort) {
        this.useCase = useCase;
        this.loadPort = loadPort;
    }

    @Operation(summary = "주문에 대한 배송 생성 (PENDING)")
    @PostMapping
    public ResponseEntity<ShipmentResponse> create(@PathVariable Long orderId,
                                                    @RequestBody AddressRequest req) {
        Shipment s = useCase.createForOrder(orderId, req.toAddress());
        return ResponseEntity.ok(ShipmentResponse.from(s));
    }

    @Operation(summary = "배송 조회")
    @GetMapping
    public ResponseEntity<ShipmentResponse> get(@PathVariable Long orderId) {
        return loadPort.loadByOrderId(orderId)
                .map(ShipmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "배송지 변경 (PENDING 만 가능)")
    @PatchMapping("/address")
    public ResponseEntity<ShipmentResponse> changeAddress(@PathVariable Long orderId,
                                                           @RequestBody AddressRequest req) {
        return ResponseEntity.ok(ShipmentResponse.from(useCase.changeAddress(orderId, req.toAddress())));
    }

    @Operation(summary = "출고 처리 — 운송장 번호 발급",
            description = "PENDING/READY → SHIPPED. 운송장 번호로 외부 추적 시스템과 연동.")
    @PostMapping("/ship")
    public ResponseEntity<ShipmentResponse> ship(@PathVariable Long orderId,
                                                  @RequestBody ShipRequest req) {
        return ResponseEntity.ok(ShipmentResponse.from(
                useCase.ship(orderId, req.carrier(), req.trackingNumber())));
    }

    @Operation(summary = "택배사 첫 스캔 (IN_TRANSIT)")
    @PostMapping("/in-transit")
    public ResponseEntity<ShipmentResponse> inTransit(@PathVariable Long orderId) {
        return ResponseEntity.ok(ShipmentResponse.from(useCase.markInTransit(orderId)));
    }

    @Operation(summary = "배송 완료 (DELIVERED)")
    @PostMapping("/delivered")
    public ResponseEntity<ShipmentResponse> delivered(@PathVariable Long orderId) {
        return ResponseEntity.ok(ShipmentResponse.from(useCase.markDelivered(orderId)));
    }

    @Operation(summary = "반품 처리 (RETURNED)")
    @PostMapping("/returned")
    public ResponseEntity<ShipmentResponse> returned(@PathVariable Long orderId) {
        return ResponseEntity.ok(ShipmentResponse.from(useCase.markReturned(orderId)));
    }

    public record AddressRequest(
            @NotBlank String recipientName,
            @NotBlank String phone,
            @NotBlank String postalCode,
            @NotBlank String address1,
            String address2,
            String deliveryMemo) {
        ShippingAddress toAddress() {
            return new ShippingAddress(recipientName, phone, postalCode, address1, address2, deliveryMemo);
        }
    }

    public record ShipRequest(@NotBlank String carrier, @NotBlank String trackingNumber) {}

    public record ShipmentResponse(Map<String, Object> shipment) {
        static ShipmentResponse from(Shipment s) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", s.getId());
            body.put("orderId", s.getOrderId());
            body.put("status", s.getStatus().name());
            body.put("recipientName", s.getAddress().recipientName());
            body.put("phone", s.getAddress().phone());
            body.put("postalCode", s.getAddress().postalCode());
            body.put("address1", s.getAddress().address1());
            body.put("address2", s.getAddress().address2());
            body.put("deliveryMemo", s.getAddress().deliveryMemo());
            body.put("carrier", s.getCarrier());
            body.put("trackingNumber", s.getTrackingNumber());
            body.put("shippedAt", s.getShippedAt());
            body.put("deliveredAt", s.getDeliveredAt());
            return new ShipmentResponse(body);
        }
    }
}
