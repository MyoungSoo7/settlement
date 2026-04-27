package github.lms.lemuel.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CashflowTotalsTest {

    @Test
    @DisplayName("빈 버킷 — 모든 합계 0, refundRate=0")
    void emptyBuckets() {
        CashflowTotals totals = CashflowTotals.from(List.of());

        assertThat(totals.transactionCount()).isZero();
        assertThat(totals.gmv()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.refundRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("여러 버킷 합 + refundRate 계산")
    void sumsAcrossBuckets() {
        CashflowBucket d1 = new CashflowBucket(
                LocalDate.of(2026, 4, 1), 10,
                new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("3000"), new BigDecimal("87000"));
        CashflowBucket d2 = new CashflowBucket(
                LocalDate.of(2026, 4, 2), 20,
                new BigDecimal("200000"), new BigDecimal("20000"),
                new BigDecimal("6000"), new BigDecimal("174000"));

        CashflowTotals totals = CashflowTotals.from(List.of(d1, d2));

        assertThat(totals.transactionCount()).isEqualTo(30L);
        assertThat(totals.gmv()).isEqualByComparingTo("300000");
        assertThat(totals.refundedAmount()).isEqualByComparingTo("30000");
        assertThat(totals.commissionAmount()).isEqualByComparingTo("9000");
        assertThat(totals.netSettlement()).isEqualByComparingTo("261000");
        // refundRate = 30000 / 300000 = 0.1000
        assertThat(totals.refundRate()).isEqualByComparingTo("0.1000");
    }

    @Test
    @DisplayName("gmv 0 일 때 refundRate 도 0 으로 방어")
    void refundRateZeroWhenGmvZero() {
        CashflowBucket b = new CashflowBucket(
                LocalDate.of(2026, 4, 1), 0,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        CashflowTotals totals = CashflowTotals.from(List.of(b));

        assertThat(totals.refundRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
