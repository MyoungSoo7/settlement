package github.lms.lemuel.tax.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TaxReconciliationCheckTest {

    @Test
    void 기대와_실제가_같으면_통과() {
        TaxReconciliationCheck c = TaxReconciliationCheck.of("x", new BigDecimal("100"), new BigDecimal("100.00"));
        assertThat(c.passed()).isTrue();
        assertThat(c.discrepancy()).isEqualByComparingTo("0");
    }

    @Test
    void 기대와_실제가_다르면_실패_차이표시() {
        TaxReconciliationCheck c = TaxReconciliationCheck.of("x", new BigDecimal("100"), new BigDecimal("90"));
        assertThat(c.passed()).isFalse();
        assertThat(c.discrepancy()).isEqualByComparingTo("-10");
    }
}
