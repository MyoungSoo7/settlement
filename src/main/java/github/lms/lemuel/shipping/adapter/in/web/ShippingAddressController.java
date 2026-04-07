package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.adapter.in.web.dto.CreateShippingAddressRequest;
import github.lms.lemuel.shipping.adapter.in.web.dto.ShippingAddressResponse;
import github.lms.lemuel.shipping.adapter.in.web.dto.UpdateShippingAddressRequest;
import github.lms.lemuel.shipping.application.port.in.ShippingAddressUseCase;
import github.lms.lemuel.shipping.domain.ShippingAddress;
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
 * 배송지 REST API
 * POST   /api/shipping-addresses                  - 배송지 생성
 * GET    /api/shipping-addresses/user/{userId}     - 사용자 배송지 목록
 * PUT    /api/shipping-addresses/{id}              - 배송지 수정
 * DELETE /api/shipping-addresses/{id}              - 배송지 삭제
 * PATCH  /api/shipping-addresses/{id}/default      - 기본 배송지 설정
 */
@Validated
@RestController
@RequestMapping("/api/shipping-addresses")
@RequiredArgsConstructor
public class ShippingAddressController {

    private final ShippingAddressUseCase shippingAddressUseCase;

    @PostMapping
    public ResponseEntity<ShippingAddressResponse> createAddress(
            @Valid @RequestBody CreateShippingAddressRequest request) {
        ShippingAddress address = shippingAddressUseCase.createAddress(
                new ShippingAddressUseCase.CreateAddressCommand(
                        request.userId(),
                        request.recipientName(),
                        request.phone(),
                        request.zipCode(),
                        request.address(),
                        request.addressDetail()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ShippingAddressResponse.from(address));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ShippingAddressResponse>> getUserAddresses(
            @PathVariable @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        List<ShippingAddressResponse> addresses = shippingAddressUseCase.getUserAddresses(userId)
                .stream().map(ShippingAddressResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(addresses);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShippingAddressResponse> updateAddress(
            @PathVariable @Positive(message = "배송지 ID는 양수여야 합니다") Long id,
            @Valid @RequestBody UpdateShippingAddressRequest request) {
        ShippingAddress updated = shippingAddressUseCase.updateAddress(id,
                new ShippingAddressUseCase.UpdateAddressCommand(
                        request.recipientName(),
                        request.phone(),
                        request.zipCode(),
                        request.address(),
                        request.addressDetail()
                )
        );
        return ResponseEntity.ok(ShippingAddressResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(
            @PathVariable @Positive(message = "배송지 ID는 양수여야 합니다") Long id) {
        shippingAddressUseCase.deleteAddress(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/default")
    public ResponseEntity<Void> setAsDefault(
            @PathVariable @Positive(message = "배송지 ID는 양수여야 합니다") Long id,
            @RequestParam @Positive(message = "유저 ID는 양수여야 합니다") Long userId) {
        shippingAddressUseCase.setDefaultAddress(userId, id);
        return ResponseEntity.ok().build();
    }
}
