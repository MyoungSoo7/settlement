package github.lms.lemuel.coupon.adapter.in.web;

import github.lms.lemuel.coupon.adapter.in.web.dto.*;
import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.domain.Coupon;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Validated
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponUseCase couponUseCase;

    /**
     * 쿠폰 생성 (관리자)
     * POST /coupons
     */
    @PostMapping
    public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CouponCreateRequest request) {
        Coupon coupon = couponUseCase.createCoupon(new CouponUseCase.CreateCouponCommand(
                request.getCode(),
                request.getType(),
                request.getDiscountValue(),
                request.getMinOrderAmount(),
                request.getMaxDiscountAmount(),
                request.getMaxUses(),
                request.getExpiresAt()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(CouponResponse.from(coupon));
    }

    /**
     * 전체 쿠폰 목록 조회 (관리자)
     * GET /coupons
     */
    @GetMapping
    public ResponseEntity<List<CouponResponse>> getAllCoupons() {
        List<CouponResponse> coupons = couponUseCase.getAllCoupons().stream()
                .map(CouponResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(coupons);
    }

    /**
     * 쿠폰 유효성 검증
     * GET /coupons/{code}/validate?userId=&amount=
     */
    @GetMapping("/{code}/validate")
    public ResponseEntity<CouponValidateResponse> validateCoupon(
            @PathVariable String code,
            @RequestParam @Positive(message = "userId는 양수여야 합니다") Long userId,
            @RequestParam @Positive(message = "주문 금액은 0보다 커야 합니다") BigDecimal amount
    ) {
        CouponUseCase.ValidateResult result = couponUseCase.validateCoupon(code, userId, amount);
        return ResponseEntity.ok(new CouponValidateResponse(
                result.valid(),
                result.message(),
                result.discountAmount(),
                result.finalAmount()
        ));
    }

    /**
     * 쿠폰 사용 처리
     * POST /coupons/{code}/usage
     */
    @PostMapping("/{code}/usage")
    public ResponseEntity<Void> useCoupon(
            @PathVariable String code,
            @Valid @RequestBody CouponUseRequest request
    ) {
        couponUseCase.useCoupon(code, request.getUserId(), request.getOrderId());
        return ResponseEntity.ok().build();
    }
}
