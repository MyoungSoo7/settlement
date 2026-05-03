package github.lms.lemuel.coupon.application.port.out;

import github.lms.lemuel.coupon.domain.Coupon;

public interface SaveCouponPort {
    Coupon save(Coupon coupon);
    void recordUsage(Long couponId, Long userId, Long orderId);
}
