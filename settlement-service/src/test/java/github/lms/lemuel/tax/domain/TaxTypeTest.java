package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxTypeTest {

    @Test
    void 개인만_원천징수_대상() {
        assertThat(TaxType.INDIVIDUAL.isWithholdingApplicable()).isTrue();
        assertThat(TaxType.BUSINESS.isWithholdingApplicable()).isFalse();
    }

    @Test
    void fromString_대소문자_공백_허용() {
        assertThat(TaxType.fromString(" individual ")).isEqualTo(TaxType.INDIVIDUAL);
        assertThat(TaxType.fromString("BUSINESS")).isEqualTo(TaxType.BUSINESS);
    }

    @Test
    void fromString_null_공백_예외() {
        assertThatThrownBy(() -> TaxType.fromString(null)).isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> TaxType.fromString("  ")).isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void fromString_알수없는값_예외() {
        assertThatThrownBy(() -> TaxType.fromString("CORP"))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("CORP");
    }
}
