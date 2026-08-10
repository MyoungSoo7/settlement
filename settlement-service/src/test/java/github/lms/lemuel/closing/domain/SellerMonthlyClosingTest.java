package github.lms.lemuel.closing.domain;

import github.lms.lemuel.closing.domain.exception.ClosingInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerMonthlyClosingTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);

    private static SellerMonthlyClosing row(String gross, String refunded, String commission,
                                            String holdback, String net) {
        return SellerMonthlyClosing.of(JULY, 77L, 3,
                new BigDecimal(gross), new BigDecimal(refunded), new BigDecimal(commission),
                new BigDecimal(holdback), new BigDecimal(net));
    }

    @Test
    void of_는_셀러_월_집계_행을_만든다() {
        SellerMonthlyClosing r = row("100000.00", "5000.00", "3500.00", "30000.00", "91500.00");

        assertThat(r.getPeriodYm()).isEqualTo("2026-07");
        assertThat(r.getSellerId()).isEqualTo(77L);
        assertThat(r.getSettlementCount()).isEqualTo(3);
        assertThat(r.getGrossAmount()).isEqualByComparingTo("100000.00");
        assertThat(r.getNetAmount()).isEqualByComparingTo("91500.00");
    }

    @Test
    void 금액_0원은_허용한다_홀드백_0퍼센트_전략셀러() {
        SellerMonthlyClosing r = row("100000.00", "0", "2000.00", "0", "98000.00");

        assertThat(r.getHoldbackAmount()).isEqualByComparingTo("0");
    }

    @Test
    void 음수_금액은_어느_필드든_불가() {
        assertThatThrownBy(() -> row("-1", "0", "0", "0", "0"))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> row("0", "-1", "0", "0", "0"))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> row("0", "0", "-1", "0", "0"))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> row("0", "0", "0", "-1", "0"))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> row("0", "0", "0", "0", "-1"))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void 금액_null_불가() {
        assertThatThrownBy(() -> SellerMonthlyClosing.of(JULY, 77L, 1,
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void period_와_셀러_식별자는_필수다() {
        assertThatThrownBy(() -> SellerMonthlyClosing.of(null, 77L, 1,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> SellerMonthlyClosing.of(JULY, null, 1,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> SellerMonthlyClosing.of(JULY, 0L, 1,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void 정산_0건_행은_불가_집계_결과에_빈_셀러가_섞이면_안_된다() {
        assertThatThrownBy(() -> SellerMonthlyClosing.of(JULY, 77L, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    // ── ClosingTotals ──────────────────────────────────────────────────

    @Test
    void ClosingTotals_sumOf_는_셀러_행들을_합산한다() {
        List<SellerMonthlyClosing> rows = List.of(
                row("100.00", "10.00", "3.50", "30.00", "86.50"),
                row("200.00", "0", "7.00", "0", "193.00"));

        ClosingTotals totals = ClosingTotals.sumOf(rows);

        assertThat(totals.grossAmount()).isEqualByComparingTo("300.00");
        assertThat(totals.refundedAmount()).isEqualByComparingTo("10.00");
        assertThat(totals.commissionAmount()).isEqualByComparingTo("10.50");
        assertThat(totals.holdbackAmount()).isEqualByComparingTo("30.00");
        assertThat(totals.netAmount()).isEqualByComparingTo("279.50");
    }

    @Test
    void ClosingTotals_sumOf_빈_목록은_전부_0원() {
        ClosingTotals totals = ClosingTotals.sumOf(List.of());

        assertThat(totals.grossAmount()).isEqualByComparingTo("0");
        assertThat(totals.netAmount()).isEqualByComparingTo("0");
    }

    @Test
    void ClosingTotals_는_음수_합계_불가() {
        assertThatThrownBy(() -> ClosingTotals.of(new BigDecimal("-1"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void ClosingTotals_는_null_합계_불가() {
        assertThatThrownBy(() -> ClosingTotals.of(null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }
}
