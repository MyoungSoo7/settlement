package github.lms.lemuel.coupon.adapter.out.persistence;

import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CouponPersistenceAdapter implements LoadCouponPort, SaveCouponPort {

    private final SpringDataCouponJpaRepository couponRepository;
    private final SpringDataCouponUsageJpaRepository usageRepository;

    @Override
    public Optional<Coupon> findByCode(String code) {
        return couponRepository.findByCode(code).map(this::toDomain);
    }

    @Override
    public List<Coupon> findAll() {
        return couponRepository.findAll().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean hasUserUsedCoupon(Long couponId, Long userId) {
        return usageRepository.existsByCouponIdAndUserId(couponId, userId);
    }

    @Override
    public Coupon save(Coupon coupon) {
        CouponJpaEntity entity = toEntity(coupon);
        CouponJpaEntity saved = couponRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void recordUsage(Long couponId, Long userId, Long orderId) {
        CouponUsageJpaEntity usage = new CouponUsageJpaEntity();
        usage.setCouponId(couponId);
        usage.setUserId(userId);
        usage.setOrderId(orderId);
        usageRepository.save(usage);
    }

    private CouponJpaEntity toEntity(Coupon domain) {
        CouponJpaEntity entity = new CouponJpaEntity();
        entity.setId(domain.getId());
        entity.setCode(domain.getCode());
        entity.setType(domain.getType().name());
        entity.setDiscountValue(domain.getDiscountValue());
        entity.setMinOrderAmount(domain.getMinOrderAmount());
        entity.setMaxUses(domain.getMaxUses());
        entity.setUsedCount(domain.getUsedCount());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setActive(domain.isActive());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private Coupon toDomain(CouponJpaEntity entity) {
        Coupon coupon = new Coupon();
        coupon.setId(entity.getId());
        coupon.setCode(entity.getCode());
        coupon.setType(CouponType.valueOf(entity.getType()));
        coupon.setDiscountValue(entity.getDiscountValue());
        coupon.setMinOrderAmount(entity.getMinOrderAmount());
        coupon.setMaxUses(entity.getMaxUses());
        coupon.setUsedCount(entity.getUsedCount());
        coupon.setExpiresAt(entity.getExpiresAt());
        coupon.setActive(entity.isActive());
        coupon.setCreatedAt(entity.getCreatedAt());
        coupon.setUpdatedAt(entity.getUpdatedAt());
        return coupon;
    }
}