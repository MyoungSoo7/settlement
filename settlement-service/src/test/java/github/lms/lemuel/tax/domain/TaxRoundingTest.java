package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxRoundingTest {

    @Test
    void 소수부는_절사한다_반올림_아님() {
        assertThat(TaxRounding.floorToWon(new BigDecimal("3184.5"))).isEqualByComparingTo("3184");
        assertThat(TaxRounding.floorToWon(new BigDecimal("399.99"))).isEqualByComparingTo("399");
        assertThat(TaxRounding.floorToWon(new BigDecimal("350.00"))).isEqualByComparingTo("350");
    }

    @Test
    void scale는_0으로_고정() {
        assertThat(TaxRounding.floorToWon(new BigDecimal("350.00")).scale()).isZero();
    }

    @Test
    void null이면_예외() {
        assertThatThrownBy(() -> TaxRounding.floorToWon(null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 음수면_예외() {
        assertThatThrownBy(() -> TaxRounding.floorToWon(new BigDecimal("-1")))
                .isInstanceOf(TaxInvariantViolationException.class);
    }
}
