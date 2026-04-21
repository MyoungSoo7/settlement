package github.lms.lemuel.coupon.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class CouponTest {

    @Test @DisplayName("정액 쿠폰 생성")
    void createFixed() {
        Coupon coupon = Coupon.create("FIXED5000", CouponType.FIXED, new BigDecimal("5000"),
                new BigDecimal("10000"), null, 100, LocalDateTime.now().plusDays(30));
        assertThat(coupon.getCode()).isEqualTo("FIXED5000");
        assertThat(coupon.getType()).isEqualTo(CouponType.FIXED);
    }

    @Test @DisplayName("정률 쿠폰 생성")
    void createPercentage() {
        Coupon coupon = Coupon.create("PERCENT10", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("5000"), 50, null);
        assertThat(coupon.getType()).isEqualTo(CouponType.PERCENTAGE);
    }

    @Test @DisplayName("코드 빈값이면 예외")
    void create_blankCode() {
        assertThatThrownBy(() -> Coupon.create("", CouponType.FIXED, new BigDecimal("1000"),
                null, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("코드");
    }

    @Test @DisplayName("할인값 0이면 예외")
    void create_zeroDiscount() {
        assertThatThrownBy(() -> Coupon.create("CODE", CouponType.FIXED, BigDecimal.ZERO,
                null, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("정률 100% 초과 시 예외")
    void create_percentageOver100() {
        assertThatThrownBy(() -> Coupon.create("CODE", CouponType.PERCENTAGE, new BigDecimal("150"),
                null, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100%");
    }

    @Test @DisplayName("최대 사용 횟수 0이면 예외")
    void create_zeroMaxUses() {
        assertThatThrownBy(() -> Coupon.create("CODE", CouponType.FIXED, new BigDecimal("1000"),
                null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("정액 할인 계산")
    void calculateDiscount_fixed() {
        Coupon coupon = Coupon.create("F", CouponType.FIXED, new BigDecimal("3000"),
                BigDecimal.ZERO, null, 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("50000"))).isEqualByComparingTo("3000");
    }

    @Test @DisplayName("정액 할인 — 주문 금액보다 크면 주문 금액까지만")
    void calculateDiscount_fixed_cappedByOrderAmount() {
        Coupon coupon = Coupon.create("F", CouponType.FIXED, new BigDecimal("10000"),
                BigDecimal.ZERO, null, 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("5000"))).isEqualByComparingTo("5000");
    }

    @Test @DisplayName("정률 할인 계산 (10%)")
    void calculateDiscount_percentage() {
        Coupon coupon = Coupon.create("P", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, null, 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("50000"))).isEqualByComparingTo("5000");
    }

    @Test @DisplayName("정률 할인 — 최대 할인 상한선 적용")
    void calculateDiscount_percentage_cappedByMax() {
        Coupon coupon = Coupon.create("P", CouponType.PERCENTAGE, new BigDecimal("50"),
                BigDecimal.ZERO, new BigDecimal("10000"), 10, null);
        assertThat(coupon.calculateDiscount(new BigDecimal("100000"))).isEqualByComparingTo("10000");
    }

    @Test @DisplayName("비활성 쿠폰 검증 실패")
    void validate_inactive() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        coupon.setActive(false);
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비활성");
    }

    @Test @DisplayName("사용 한도 초과 시 검증 실패")
    void validate_exceededUsage() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 1, null);
        coupon.incrementUsage();
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한도");
    }

    @Test @DisplayName("최소 주문 금액 미달 시 검증 실패")
    void validate_belowMinOrder() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                new BigDecimal("50000"), null, 10, null);
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("30000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최소 주문");
    }

    @Test @DisplayName("만료된 쿠폰 검증 실패")
    void validate_expired() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, LocalDateTime.now().minusDays(1));
        assertThatThrownBy(() -> coupon.validate(new BigDecimal("50000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("만료");
    }

    @Test @DisplayName("사용 횟수 증가")
    void incrementUsage() {
        Coupon coupon = Coupon.create("C", CouponType.FIXED, new BigDecimal("1000"),
                BigDecimal.ZERO, null, 10, null);
        coupon.incrementUsage();
        coupon.incrementUsage();
        assertThat(coupon.getUsedCount()).isEqualTo(2);
    }

    @Test @DisplayName("부분 환불 할인 계산")
    void calculateDiscountForRefund() {
        Coupon coupon = Coupon.create("F", CouponType.FIXED, new BigDecimal("10000"),
                BigDecimal.ZERO, null, 10, null);
        // 전체 주문 50000에서 10000 할인, 환불 대상 25000
        BigDecimal refundDiscount = coupon.calculateDiscountForRefund(
                new BigDecimal("50000"), new BigDecimal("25000"));
        // 10000 * 25000 / 50000 = 5000
        assertThat(refundDiscount).isEqualByComparingTo("5000");
    }
}
