package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxInvoiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 세무계산으로_발행_공급가액_세액_합계_포함과세() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.INDIVIDUAL);
        TaxInvoice invoice = TaxInvoice.issue(123L, 7L, calc, DATE);

        assertThat(invoice.getSettlementId()).isEqualTo(123L);
        assertThat(invoice.getSellerId()).isEqualTo(7L);
        assertThat(invoice.getSupplyAmount()).isEqualByComparingTo("3182"); // commission(3500) - vat(318)
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("318");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("3500.00"); // = commission (포함과세 항등식)
        assertThat(invoice.getIssueDate()).isEqualTo(DATE);
        assertThat(invoice.getIssueNumber()).isEqualTo("TI-0000000123");
        assertThat(invoice.getCreatedAt()).isNotNull();
    }

    @Test
    void 발행번호는_정산에서_결정적_파생_멱등키() {
        assertThat(TaxInvoice.numberFor(123L)).isEqualTo("TI-0000000123");
        assertThat(TaxInvoice.numberFor(123L)).isEqualTo(TaxInvoice.numberFor(123L));
        assertThat(TaxInvoice.numberFor(999L)).isNotEqualTo(TaxInvoice.numberFor(123L));
    }

    @Test
    void calc_null_예외() {
        assertThatThrownBy(() -> TaxInvoice.issue(1L, 1L, null, DATE))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void settlementId_sellerId_비양수_예외() {
        TaxCalculation calc = TaxCalculation.of(bd("100"), bd("100"), TaxType.BUSINESS);
        assertThatThrownBy(() -> TaxInvoice.issue(0L, 1L, calc, DATE))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> TaxInvoice.issue(1L, 0L, calc, DATE))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void issueDate_null_예외() {
        TaxCalculation calc = TaxCalculation.of(bd("100"), bd("100"), TaxType.BUSINESS);
        assertThatThrownBy(() -> TaxInvoice.issue(1L, 1L, calc, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void assignId_1회만_허용() {
        TaxCalculation calc = TaxCalculation.of(bd("100"), bd("100"), TaxType.BUSINESS);
        TaxInvoice invoice = TaxInvoice.issue(1L, 1L, calc, DATE);
        invoice.assignId(55L);
        assertThat(invoice.getId()).isEqualTo(55L);
        assertThatThrownBy(() -> invoice.assignId(66L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rehydrate는_저장값_그대로() {
        LocalDateTime ts = LocalDateTime.of(2026, 7, 23, 10, 0);
        TaxInvoice invoice = TaxInvoice.rehydrate(5L, 1L, 7L, bd("3500.00"), bd("350"), bd("3850.00"),
                DATE, "TI-0000000001", ts);
        assertThat(invoice.getId()).isEqualTo(5L);
        assertThat(invoice.getIssueNumber()).isEqualTo("TI-0000000001");
        assertThat(invoice.getCreatedAt()).isEqualTo(ts);
    }
}
