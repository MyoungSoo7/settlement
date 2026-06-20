package github.lms.lemuel.coupon.application.port.out;

import github.lms.lemuel.coupon.domain.Coupon;

public interface SaveCouponPort {
    Coupon save(Coupon coupon);
    void recordUsage(Long couponId, Long userId, Long orderId);

    /**
     * 사용 한도 내에서만 사용 횟수를 원자적으로 1 증가시킨다.
     * @return 증가에 성공하면 true, 이미 소진되었으면 false
     */
    boolean incrementUsageIfAvailable(Long couponId);
}
