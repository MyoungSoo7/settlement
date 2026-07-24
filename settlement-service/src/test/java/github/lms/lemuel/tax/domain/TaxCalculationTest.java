package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TaxCalculation 도메인 단위 테스트 — 부가세(포함과세)·원천징수·실지급 산출 + 원단위 절사 경계 + 항등식
 * (2026-07-24 정정 — VAT 포함과세로 재구현).
 */
class TaxCalculationTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 개인_셀러_부가세와_원천징수_모두_산출() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.INDIVIDUAL);

        assertThat(calc.commission()).isEqualByComparingTo("3500.00");
        assertThat(calc.vatAmount()).isEqualByComparingTo("318");         // floor(3500*10/110=318.1818...)
        assertThat(calc.supplyAmount()).isEqualByComparingTo("3182");     // 3500 - 318
        assertThat(calc.withholdingAmount()).isEqualByComparingTo("3184"); // floor(96500*0.033=3184.5)
        assertThat(calc.netPayable()).isEqualByComparingTo("93316");      // 96500 - 3184
        assertThat(calc.invoiceTotal()).isEqualByComparingTo("3500");     // 3182 + 318 = commission
        assertThat(calc.hasVat()).isTrue();
        assertThat(calc.hasWithholding()).isTrue();
    }

    @Test
    void 사업자_셀러_원천징수는_0_실지급은_net_그대로() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.BUSINESS);

        assertThat(calc.vatAmount()).isEqualByComparingTo("318");
        assertThat(calc.supplyAmount()).isEqualByComparingTo("3182");
        assertThat(calc.withholdingAmount()).isEqualByComparingTo("0");
        assertThat(calc.netPayable()).isEqualByComparingTo("96500.00");
        assertThat(calc.hasWithholding()).isFalse();
    }

    @Test
    void 부가세는_포함과세_10_110_원단위_절사한다() {
        // 3999 * 10/110 = 363.5454... → floor 363
        TaxCalculation calc = TaxCalculation.of(bd("3999.00"), bd("100000.00"), TaxType.BUSINESS);
        assertThat(calc.vatAmount()).isEqualByComparingTo("363");
        assertThat(calc.supplyAmount()).isEqualByComparingTo("3636"); // 3999 - 363
        assertThat(calc.invoiceTotal()).isEqualByComparingTo("3999.00"); // = commission
    }

    @Test
    void 원천징수는_원단위_절사한다() {
        // 96500 * 0.033 = 3184.5 → floor 3184 (반올림 아님)
        TaxCalculation calc = TaxCalculation.of(bd("100.00"), bd("96500.00"), TaxType.INDIVIDUAL);
        assertThat(calc.withholdingAmount()).isEqualByComparingTo("3184");
    }

    @Test
    void 수수료가_작으면_부가세_0_예수없음() {
        // 10 * 10/110 = 0.909... → floor 0
        TaxCalculation calc = TaxCalculation.of(bd("10.00"), bd("100.00"), TaxType.BUSINESS);
        assertThat(calc.vatAmount()).isEqualByComparingTo("0");
        assertThat(calc.supplyAmount()).isEqualByComparingTo("10.00"); // vat=0 이면 supply=commission
        assertThat(calc.hasVat()).isFalse();
    }

    @Test
    void 금액_0_경계는_모두_0() {
        TaxCalculation calc = TaxCalculation.of(bd("0.00"), bd("0.00"), TaxType.INDIVIDUAL);
        assertThat(calc.vatAmount()).isEqualByComparingTo("0");
        assertThat(calc.supplyAmount()).isEqualByComparingTo("0.00");
        assertThat(calc.withholdingAmount()).isEqualByComparingTo("0");
        assertThat(calc.netPayable()).isEqualByComparingTo("0.00");
        assertThat(calc.hasVat()).isFalse();
        assertThat(calc.hasWithholding()).isFalse();
    }

    @Test
    void 공급가액_세액_합은_항상_commission과_일치() {
        for (String commission : new String[]{"1", "10", "99", "3500.00", "999999.99"}) {
            TaxCalculation calc = TaxCalculation.of(bd(commission), bd("1000.00"), TaxType.BUSINESS);
            assertThat(calc.supplyAmount().add(calc.vatAmount()))
                    .as("commission=%s", commission)
                    .isEqualByComparingTo(bd(commission));
        }
    }

    @Test
    void computeWithholding_단독호출_개인만_양수() {
        assertThat(TaxCalculation.computeWithholding(bd("96500.00"), TaxType.INDIVIDUAL))
                .isEqualByComparingTo("3184");
        assertThat(TaxCalculation.computeWithholding(bd("96500.00"), TaxType.BUSINESS))
                .isEqualByComparingTo("0");
    }

    @Test
    void computeWithholding_인자_검증() {
        assertThatThrownBy(() -> TaxCalculation.computeWithholding(bd("100"), null))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> TaxCalculation.computeWithholding(bd("-1"), TaxType.INDIVIDUAL))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> TaxCalculation.computeWithholding(null, TaxType.INDIVIDUAL))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void taxType_null이면_예외() {
        assertThatThrownBy(() -> TaxCalculation.of(bd("100"), bd("100"), null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void commission_음수면_예외() {
        assertThatThrownBy(() -> TaxCalculation.of(bd("-1"), bd("100"), TaxType.BUSINESS))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("commission");
    }

    @Test
    void netAmount_음수면_예외() {
        assertThatThrownBy(() -> TaxCalculation.of(bd("100"), bd("-1"), TaxType.BUSINESS))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("netAmount");
    }

    @Test
    void 동일_입력은_equals_hashCode_일치() {
        TaxCalculation a = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.INDIVIDUAL);
        TaxCalculation b = TaxCalculation.of(bd("3500.0000"), bd("96500.000"), TaxType.INDIVIDUAL);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("x");
        TaxCalculation c = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.BUSINESS);
        assertThat(a).isNotEqualTo(c);
    }
}
