package github.lms.lemuel.coupon.domain;

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
    private String targetType;
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
            throw new IllegalArgumentException("쿠폰 코드는 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("쿠폰 타입은 필수입니다.");
        }
        if (discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("할인 금액은 0보다 커야 합니다.");
        }
        if (type == CouponType.PERCENTAGE && discountValue.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("정률 할인은 100%를 초과할 수 없습니다.");
        }
        if (maxUses <= 0) {
            throw new IllegalArgumentException("최대 사용 횟수는 1 이상이어야 합니다.");
        }

        Coupon coupon = new Coupon();
        coupon.code = code.toUpperCase().trim();
        coupon.type = type;
        coupon.discountValue = discountValue;
        coupon.minOrderAmount = minOrderAmount != null ? minOrderAmount : BigDecimal.ZERO;
        coupon.maxDiscountAmount = maxDiscountAmount;
        coupon.maxUses = maxUses;
        coupon.targetType = "ALL";
        coupon.expiresAt = expiresAt;
        return coupon;
    }

    public void configureTarget(String targetType, Long targetId) {
        String normalized = targetType == null || targetType.isBlank()
                ? "ALL"
                : targetType.trim().toUpperCase();
        if (!normalized.equals("ALL") && !normalized.equals("CATEGORY") && !normalized.equals("PRODUCT")) {
            throw new IllegalArgumentException("지원하지 않는 쿠폰 적용 대상입니다: " + targetType);
        }
        if (!normalized.equals("ALL") && targetId == null) {
            throw new IllegalArgumentException("특정 대상 쿠폰은 targetId가 필요합니다.");
        }
        this.targetType = normalized;
        this.targetId = normalized.equals("ALL") ? null : targetId;
    }

    public void configurePeriod(LocalDateTime startsAt, LocalDateTime expiresAt) {
        if (startsAt != null && expiresAt != null && startsAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("쿠폰 시작일은 종료일보다 늦을 수 없습니다.");
        }
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 쿠폰 유효성 검사
     */
    public void validate(BigDecimal orderAmount) {
        if (!isActive) {
            throw new IllegalStateException("비활성화된 쿠폰입니다.");
        }
        if (usedCount >= maxUses) {
            throw new IllegalStateException("쿠폰 사용 한도를 초과했습니다.");
        }
        if (startsAt != null && LocalDateTime.now().isBefore(startsAt)) {
            throw new IllegalStateException("아직 사용할 수 없는 쿠폰입니다.");
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            throw new IllegalStateException("만료된 쿠폰입니다.");
        }
        if (orderAmount.compareTo(minOrderAmount) < 0) {
            throw new IllegalStateException(
                String.format("최소 주문 금액(%,.0f원) 이상이어야 합니다.", minOrderAmount));
        }
    }

    /**
     * 할인 금액 계산
     */
    public BigDecimal calculateDiscount(BigDecimal orderAmount) {
        BigDecimal discount;
        if (type == CouponType.FIXED) {
            discount = discountValue.min(orderAmount);
        } else {
            discount = orderAmount.multiply(discountValue)
                    .divide(new BigDecimal("100"), 0, RoundingMode.FLOOR);
        }

        if (maxDiscountAmount != null && discount.compareTo(maxDiscountAmount) > 0) {
            return maxDiscountAmount;
        }
        return discount;
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

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public CouponType getType() { return type; }
    public void setType(CouponType type) { this.type = type; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMinOrderAmount() { return minOrderAmount; }
    public void setMinOrderAmount(BigDecimal minOrderAmount) { this.minOrderAmount = minOrderAmount; }

    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }
    public void setMaxDiscountAmount(BigDecimal maxDiscountAmount) { this.maxDiscountAmount = maxDiscountAmount; }

    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }

    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }

    public String getTargetType() { return targetType == null ? "ALL" : targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public LocalDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(LocalDateTime startsAt) { this.startsAt = startsAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
