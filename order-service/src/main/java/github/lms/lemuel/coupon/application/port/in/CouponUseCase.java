package github.lms.lemuel.coupon.application.port.in;

import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface CouponUseCase {

    Coupon createCoupon(CreateCouponCommand command);

    /**
     * 쿠폰 검증: 코드, 사용자 중복 사용 여부, 주문 금액 조건 확인
     * 유효하면 할인 금액을 포함한 Coupon 반환
     */
    ValidateResult validateCoupon(String code, Long userId, BigDecimal orderAmount);

    /**
     * 쿠폰 사용 처리: 사용 횟수 증가 + 사용 내역 기록
     */
    void useCoupon(String code, Long userId, Long orderId);

    List<Coupon> getAllCoupons();

    record CreateCouponCommand(
            String code,
            CouponType type,
            BigDecimal discountValue,
            BigDecimal minOrderAmount,
            BigDecimal maxDiscountAmount,
            int maxUses,
            LocalDateTime expiresAt
    ) {}

    record ValidateResult(
            boolean valid,
            String message,
            BigDecimal discountAmount,
            BigDecimal finalAmount,
            Coupon coupon
    ) {}
}
