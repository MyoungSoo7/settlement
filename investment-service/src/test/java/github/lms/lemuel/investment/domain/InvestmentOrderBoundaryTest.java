package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 투자 주문 애그리거트의 금액·시각 경계값 회귀 가드.
 *
 * <p>웹 계층 {@code @Digits(integer=17, fraction=2)} 상한(=NUMERIC(19,2) 저장 정밀도)에 대응하는 도메인
 * 동작을 고정한다: 정수 17자리 상한 값은 도메인이 수용하고, 소수 3자리 이상은 scale 2 HALF_UP 로 흡수한다.
 * 신청 시각(createdAt) 오버로드는 넘긴 값을 그대로 보존하고 null 은 거부한다(Clock 주입 경로의 결정성).
 */
class InvestmentOrderBoundaryTest {

    @Test
    void 정수17자리_소수2자리_상한금액은_수용되고_scale2로_보존된다() {
        // 99999999999999999.99 — @Digits(17,2) 상한 근처. 도메인은 이 크기를 정상 수용한다.
        BigDecimal upper = new BigDecimal("99999999999999999.99");
        InvestmentOrder order = InvestmentOrder.request(7L, "005930", upper, 82, "AA");
        assertThat(order.getAmount()).isEqualByComparingTo(upper);
        assertThat(order.getAmount().scale()).isEqualTo(2);
    }

    @Test
    void 소수3자리_금액은_scale2_HALF_UP로_정규화된다() {
        InvestmentOrder order = InvestmentOrder.request(7L, "005930", new BigDecimal("1000.555"), 82, "AA");
        assertThat(order.getAmount()).isEqualByComparingTo("1000.56");
        assertThat(order.getAmount().scale()).isEqualTo(2);
    }

    @Test
    void 금액이_0이하면_예외() {
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "005930", BigDecimal.ZERO, 82, "AA"))
                .isInstanceOf(InvestmentInvariantViolationException.class);
        assertThatThrownBy(() -> InvestmentOrder.request(7L, "005930", new BigDecimal("-1"), 82, "AA"))
                .isInstanceOf(InvestmentInvariantViolationException.class);
    }

    @Test
    void createdAt_오버로드는_넘긴_시각을_그대로_보존하고_null이면_예외() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 16, 9, 30, 0);
        InvestmentOrder order = InvestmentOrder.request(7L, "005930", new BigDecimal("1000000"), 82, "AA", at);
        assertThat(order.getCreatedAt()).isEqualTo(at);

        assertThatThrownBy(() -> InvestmentOrder.request(7L, "005930", new BigDecimal("1000000"), 82, "AA", null))
                .isInstanceOf(InvestmentInvariantViolationException.class);
    }
}
