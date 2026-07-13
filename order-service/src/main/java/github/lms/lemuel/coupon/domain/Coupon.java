package github.lms.lemuel.coupon.domain;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;
import github.lms.lemuel.coupon.domain.exception.InvalidCouponStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Coupon Domain Entity (순수 POJO)
 */
public class Coupon {

    private Long id;
    private String code;
    private CouponType type;
    private BigDecimal discountValue;
    private BigDecimal minOrderAmount;
    private BigDecimal maxDiscountAmount; // 할인 상한선 (null이면 무제한)
    private int maxUses;
    private int usedCount;
    private CouponTarget targetType;
    private Long targetId;
    private LocalDateTime startsAt;
    private LocalDateTime expiresAt;
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Coupon() {
        this.usedCount = 0;
        this.isActive = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static Coupon create(String code, CouponType type, BigDecimal discountValue,
                                BigDecimal minOrderAmount, BigDecimal maxDiscountAmount,
                                int maxUses, LocalDateTime expiresAt) {
        if (code == null || code.isBlank()) {
            throw new CouponInvariantViolationException("쿠폰 코드는 필수입니다.");
        }
        if (type == null) {
            throw new CouponInvariantViolationException("쿠폰 타입은 필수입니다.");
        }
        if (discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CouponInvariantViolationException("할인 금액은 0보다 커야 합니다.");
        }
        type.validateDiscountValue(discountValue); // 타입별 제약 검증을 Strategy 에 위임
        if (maxUses <= 0) {
            throw new CouponInvariantViolationException("최대 사용 횟수는 1 이상이어야 합니다.");
        }

        Coupon coupon = new Coupon();
        coupon.code = code.toUpperCase().trim();
        coupon.type = type;
        coupon.discountValue = discountValue;
        coupon.minOrderAmount = minOrderAmount != null ? minOrderAmount : BigDecimal.ZERO;
        coupon.maxDiscountAmount = maxDiscountAmount;
        coupon.maxUses = maxUses;
        coupon.targetType = CouponTarget.ALL;
        coupon.expiresAt = expiresAt;
        return coupon;
    }

    public void configureTarget(String targetType, Long targetId) {
        CouponTarget target = CouponTarget.fromInput(targetType);
        if (target.requiresTargetId() && targetId == null) {
            throw new CouponInvariantViolationException("특정 대상 쿠폰은 targetId가 필요합니다.");
        }
        this.targetType = target;
        this.targetId = target == CouponTarget.ALL ? null : targetId;
    }

    public void configurePeriod(LocalDateTime startsAt, LocalDateTime expiresAt) {
        if (startsAt != null && expiresAt != null && startsAt.isAfter(expiresAt)) {
            throw new CouponInvariantViolationException("쿠폰 시작일은 종료일보다 늦을 수 없습니다.");
        }
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 쿠폰 유효성 검사
     */
    public void validate(BigDecimal orderAmount) {
        if (!isActive) {
            throw new InvalidCouponStateException("비활성화된 쿠폰입니다.");
        }
        if (usedCount >= maxUses) {
            throw new InvalidCouponStateException("쿠폰 사용 한도를 초과했습니다.");
        }
        if (startsAt != null && LocalDateTime.now().isBefore(startsAt)) {
            throw new InvalidCouponStateException("아직 사용할 수 없는 쿠폰입니다.");
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            throw new InvalidCouponStateException("만료된 쿠폰입니다.");
        }
        if (orderAmount.compareTo(minOrderAmount) < 0) {
            throw new InvalidCouponStateException(
                String.format("최소 주문 금액(%,.0f원) 이상이어야 합니다.", minOrderAmount));
        }
    }

    /**
     * 할인 금액 계산
     */
    public BigDecimal calculateDiscount(BigDecimal orderAmount) {
        BigDecimal discount = type.rawDiscount(discountValue, orderAmount); // 타입별 계산을 Strategy 에 위임
        if (maxDiscountAmount != null && discount.compareTo(maxDiscountAmount) > 0) {
            return maxDiscountAmount;
        }
        return discount;
    }

    /**
     * 이 쿠폰이 주어진 상품/카테고리 주문에 적용 가능한 대상인지 판정한다.
     *
     * <p>적용 대상 매칭 규칙은 {@link CouponTarget} enum-Strategy 가 캡슐화한다 — 도메인/서비스에서
     * targetType 문자열을 꺼내 분기하던 로직을 타입 자체로 흡수.
     */
    public boolean appliesTo(Long productId, Long categoryId) {
        return getTargetType().matches(targetId, productId, categoryId);
    }

    /**
     * 부분 환불 시 해당 부분에 적용된 할인 금액 계산
     * (전체 할인 금액 * (환불 대상 금액 / 전체 주문 금액))
     */
    public BigDecimal calculateDiscountForRefund(BigDecimal originalOrderAmount, BigDecimal refundOriginalAmount) {
        if (originalOrderAmount.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        
        BigDecimal totalDiscount = calculateDiscount(originalOrderAmount);
        
        // (totalDiscount * refundOriginalAmount) / originalOrderAmount
        return totalDiscount.multiply(refundOriginalAmount)
                .divide(originalOrderAmount, 0, RoundingMode.FLOOR);
    }

    /**
     * 사용 횟수 증가
     */
    public void incrementUsage() {
        this.usedCount++;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 쿠폰 활성화/비활성화. 상태 플래그를 setter 로 노출하지 않고 의미 있는 도메인 동작으로만 변경.
     */
    public void activate() {
        this.isActive = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 영속 레코드 복원 팩토리. 어댑터가 no-arg + setter 대신 이 경로로만 도메인을 재구성한다.
     */
    public static Coupon rehydrate(Long id, String code, CouponType type, BigDecimal discountValue,
                                   BigDecimal minOrderAmount, BigDecimal maxDiscountAmount,
                                   int maxUses, int usedCount, CouponTarget targetType, Long targetId,
                                   LocalDateTime startsAt, LocalDateTime expiresAt, boolean isActive,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        Coupon coupon = new Coupon();
        coupon.id = id;
        coupon.code = code;
        coupon.type = type;
        coupon.discountValue = discountValue;
        coupon.minOrderAmount = minOrderAmount;
        coupon.maxDiscountAmount = maxDiscountAmount;
        coupon.maxUses = maxUses;
        coupon.usedCount = usedCount;
        coupon.targetType = targetType;
        coupon.targetId = targetId;
        coupon.startsAt = startsAt;
        coupon.expiresAt = expiresAt;
        coupon.isActive = isActive;
        coupon.createdAt = createdAt;
        coupon.updatedAt = updatedAt;
        return coupon;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id) { this.id = id; }

    // Getters
    public Long getId() { return id; }

    public String getCode() { return code; }

    public CouponType getType() { return type; }

    public BigDecimal getDiscountValue() { return discountValue; }

    public BigDecimal getMinOrderAmount() { return minOrderAmount; }

    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }

    public int getMaxUses() { return maxUses; }

    public int getUsedCount() { return usedCount; }

    public CouponTarget getTargetType() { return targetType == null ? CouponTarget.ALL : targetType; }

    public Long getTargetId() { return targetId; }

    public LocalDateTime getStartsAt() { return startsAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }

    public boolean isActive() { return isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
