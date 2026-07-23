package github.lms.lemuel.tax.domain;

import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-07-24 정정 — TaxReconciliation 실효화: 원천징수 축은 더 이상 settlement 자체원장의 자기참조 예수 합이
 * 아니라, 실제 IMMEDIATE payout 금액과의 교차검증으로 대체됐다(독립 GL 감사 지적 반영).
 */
class TaxReconciliationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private TaxCalculation individualCalc() {
        return TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.INDIVIDUAL);
    }

    @Test
    void 개인_셀러_VAT_정합_payout_감액분도_정확하면_matched() {
        TaxCalculation calc = individualCalc(); // vat=318, withholding=3184
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);

        // 실제 payout: immediate=96500(홀드백 없음), 실지급=96500-3184=93316 → 감액분=3184=withholding 일치.
        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, entries,
                bd("96500"), bd("93316"));

        assertThat(recon.matched()).isTrue();
        assertThat(recon.ledgerBalanced()).isTrue();
        assertThat(recon.ledgerVatAccrued()).isEqualByComparingTo("318");
        assertThat(recon.actualWithholdingDeducted()).isEqualByComparingTo("3184");
        assertThat(recon.mismatches()).isEmpty();
        assertThat(recon.checks()).isNotEmpty();
    }

    @Test
    void 사업자_셀러_원천징수_0_실제감액도_0이면_matched() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.BUSINESS);
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);

        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, entries,
                bd("96500"), bd("96500")); // 사업자 — 감액 없음

        assertThat(recon.matched()).isTrue();
        assertThat(recon.actualWithholdingDeducted()).isEqualByComparingTo("0");
    }

    @Test
    void 실제_payout이_원천징수를_반영하지_않으면_HIGH4_결함_검출() {
        // 장부는 계산했지만(withholding=3184) 실제 송금은 net 전액이 나간 결함 재현.
        TaxCalculation calc = individualCalc();
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);

        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, entries,
                bd("96500"), bd("96500")); // 감액 없이 전액 지급 — actual=0 ≠ expected(3184)

        assertThat(recon.matched()).isFalse();
        assertThat(recon.actualWithholdingDeducted()).isEqualByComparingTo("0");
        assertThat(recon.mismatches()).anyMatch(c -> c.name().equals("원천징수=실제payout감액분"));
    }

    @Test
    void payout이_아직_없으면_원천징수_교차검증은_평가하지_않는다() {
        TaxCalculation calc = individualCalc();
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);

        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, entries, bd("96500"), null);

        assertThat(recon.actualWithholdingDeducted()).isNull();
        // 나머지 축(VAT·공급가액 등)이 모두 정합이면 matched — payout 미존재는 억지 실패 대상이 아니다.
        assertThat(recon.matched()).isTrue();
        assertThat(recon.checks()).noneMatch(c -> c.name().equals("원천징수=실제payout감액분"));
    }

    @Test
    void 세금계산서_세액이_틀리면_불일치() {
        TaxCalculation calc = individualCalc();
        // 세액을 임의로 틀리게 만든 계산서 (포함과세 항등식도 깨진 rehydrate — 순수 데이터 불일치 재현).
        TaxInvoice wrongInvoice = TaxInvoice.rehydrate(1L, 100L, 7L, bd("3182"), bd("999"),
                bd("4181"), DATE, "TI-0000000100", null);
        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);

        TaxReconciliation recon = TaxReconciliation.reconcile(calc, wrongInvoice, entries, null, null);

        assertThat(recon.matched()).isFalse();
        assertThat(recon.mismatches()).isNotEmpty();
    }

    @Test
    void 원장_전표가_없으면_VAT예수합_0_불일치() {
        TaxCalculation calc = individualCalc();
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);

        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, List.of(), null, null);

        assertThat(recon.matched()).isFalse();
        assertThat(recon.ledgerVatAccrued()).isEqualByComparingTo("0");
    }

    @Test
    void 역분개된_전표는_예수합에서_제외되고_불균형() {
        TaxCalculation calc = individualCalc();
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);
        entries.get(0).reverse(); // VAT 전표 역분개

        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, entries, null, null);

        assertThat(recon.ledgerVatAccrued()).isEqualByComparingTo("0");
        assertThat(recon.ledgerBalanced()).isFalse();
        assertThat(recon.matched()).isFalse();
    }

    @Test
    void null_entries는_빈목록으로_처리() {
        TaxCalculation calc = individualCalc();
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        TaxReconciliation recon = TaxReconciliation.reconcile(calc, invoice, null, null, null);
        assertThat(recon.ledgerVatAccrued()).isEqualByComparingTo("0");
    }

    @Test
    void calc_또는_invoice_null_예외() {
        TaxCalculation calc = individualCalc();
        TaxInvoice invoice = TaxInvoice.issue(100L, 7L, calc, DATE);
        assertThatThrownBy(() -> TaxReconciliation.reconcile(null, invoice, List.of(), null, null))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> TaxReconciliation.reconcile(calc, null, List.of(), null, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }
}
