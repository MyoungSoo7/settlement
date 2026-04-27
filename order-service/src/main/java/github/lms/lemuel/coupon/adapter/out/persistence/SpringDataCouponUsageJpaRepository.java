package github.lms.lemuel.coupon.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataCouponUsageJpaRepository extends JpaRepository<CouponUsageJpaEntity, Long> {
    boolean existsByCouponIdAndUserId(Long couponId, Long userId);
}