package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class CommissionCalculationTest {

    @Test
    @DisplayName("기본 수수료율 3%로 수수료를 계산한다")
    void 기본_수수료_계산() {
        CommissionCalculation calc = CommissionCalculation.calculate(
                new BigDecimal("10000"), new BigDecimal("0.03"));

        assertThat(calc.paymentAmount()).isEqualByComparingTo("10000");
        assertThat(calc.commissionRate()).isEqualByComparingTo("0.03");
        assertThat(calc.commissionAmount()).isEqualByComparingTo("300.00");
        assertThat(calc.netAmount()).isEqualByComparingTo("9700.00");
    }

    @Test
    @DisplayName("VIP 수수료율 2.5%로 수수료를 계산한다")
    void VIP_수수료_계산() {
        CommissionCalculation calc = CommissionCalculation.calculate(
                new BigDecimal("10000"), new BigDecimal("0.025"));

        assertThat(calc.commissionAmount()).isEqualByComparingTo("250.00");
        assertThat(calc.netAmount()).isEqualByComparingTo("9750.00");
    }

    @Test
    @DisplayName("소수점 반올림 처리")
    void 소수점_반올림() {
        CommissionCalculation calc = CommissionCalculation.calculate(
                new BigDecimal("333"), new BigDecimal("0.03"));

        // 333 * 0.03 = 9.99
        assertThat(calc.commissionAmount()).isEqualByComparingTo("9.99");
        assertThat(calc.netAmount()).isEqualByComparingTo("323.01");
    }

    @Test
    @DisplayName("금액이 0이하이면 예외를 던진다")
    void 금액_검증() {
        assertThatThrownBy(() -> CommissionCalculation.calculate(
                BigDecimal.ZERO, new BigDecimal("0.03")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("수수료율이 범위 밖이면 예외를 던진다")
    void 수수료율_검증() {
        assertThatThrownBy(() -> CommissionCalculation.calculate(
                new BigDecimal("10000"), new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
