package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.domain.Coupon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CouponService implements CouponUseCase {

    private final LoadCouponPort loadCouponPort;
    private final SaveCouponPort saveCouponPort;

    @Override
    public Coupon createCoupon(CreateCouponCommand command) {
        Coupon coupon = Coupon.create(
                command.code(),
                command.type(),
                command.discountValue(),
                command.minOrderAmount(),
                command.maxDiscountAmount(),
                command.maxUses(),
                command.expiresAt()
        );
        return saveCouponPort.save(coupon);
    }

    @Override
    @Transactional(readOnly = true)
    public ValidateResult validateCoupon(String code, Long userId, BigDecimal orderAmount) {
        Coupon coupon = loadCouponPort.findByCode(code.toUpperCase().trim())
                .orElse(null);

        if (coupon == null) {
            return new ValidateResult(false, "존재하지 않는 쿠폰 코드입니다.", BigDecimal.ZERO, orderAmount, null);
        }

        // 이미 사용한 쿠폰인지 확인
        if (loadCouponPort.hasUserUsedCoupon(coupon.getId(), userId)) {
            return new ValidateResult(false, "이미 사용한 쿠폰입니다.", BigDecimal.ZERO, orderAmount, null);
        }

        try {
            coupon.validate(orderAmount);
        } catch (IllegalStateException e) {
            return new ValidateResult(false, e.getMessage(), BigDecimal.ZERO, orderAmount, null);
        }

        BigDecimal discount = coupon.calculateDiscount(orderAmount);
        BigDecimal finalAmount = orderAmount.subtract(discount);

        log.info("쿠폰 검증 성공: code={}, userId={}, discount={}", code, userId, discount);
        return new ValidateResult(true, "쿠폰이 적용되었습니다.", discount, finalAmount, coupon);
    }

    @Override
    public void useCoupon(String code, Long userId, Long orderId) {
        Coupon coupon = loadCouponPort.findByCode(code.toUpperCase().trim())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰: " + code));

        coupon.incrementUsage();
        saveCouponPort.save(coupon);
        saveCouponPort.recordUsage(coupon.getId(), userId, orderId);

        log.info("쿠폰 사용 완료: code={}, userId={}, orderId={}", code, userId, orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> getAllCoupons() {
        return loadCouponPort.findAll();
    }
}
