package github.lms.lemuel.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전기 대비 증감 — "이번 기간이 직전 같은 길이의 기간보다 나았는가".
 *
 * <p>핵심 판단: 직전 기간이 0 이면 증감률은 <b>없다(null)</b>. 0% 라고 답하면
 * "변화 없음"으로 읽혀 정반대의 사실을 전달하고, ∞ 로 답하면 화면이 그릴 수 없다.
 */
class SalesComparisonTest {

    private static CashflowTotals totals(long count, String gmv, String net) {
        return new CashflowTotals(count, new BigDecimal(gmv), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(net), BigDecimal.ZERO);
    }

    @Nested
    @DisplayName("증감률")
    class GrowthRate {

        @Test
        @DisplayName("두 배로 늘면 +1.0000 이다")
        void doubled() {
            SalesComparison comparison = new SalesComparison(
                    totals(20, "2000", "1800"), totals(10, "1000", "900"));

            assertThat(comparison.gmvGrowthRate()).isEqualByComparingTo("1.0000");
            assertThat(comparison.netGrowthRate()).isEqualByComparingTo("1.0000");
            assertThat(comparison.countGrowthRate()).isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("줄면 음수로 나온다")
        void decreased() {
            SalesComparison comparison = new SalesComparison(
                    totals(5, "750", "700"), totals(10, "1000", "1000"));

            assertThat(comparison.gmvGrowthRate()).isEqualByComparingTo("-0.2500");
            assertThat(comparison.countGrowthRate()).isEqualByComparingTo("-0.5000");
        }

        @Test
        @DisplayName("변화가 없으면 0 이다")
        void unchanged() {
            SalesComparison comparison = new SalesComparison(
                    totals(10, "1000", "900"), totals(10, "1000", "900"));

            assertThat(comparison.gmvGrowthRate()).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("소수 넷째 자리에서 반올림한다")
        void roundsToFourDecimals() {
            SalesComparison comparison = new SalesComparison(
                    totals(1, "1000", "1000"), totals(1, "3000", "3000"));

            assertThat(comparison.gmvGrowthRate()).isEqualByComparingTo("-0.6667");
        }
    }

    @Nested
    @DisplayName("직전 기간이 비어 있을 때")
    class NoBaseline {

        @Test
        @DisplayName("직전 매출이 0 이면 증감률은 없다 — 0% 라고 하면 거짓말이 된다")
        void zeroPreviousGmv() {
            SalesComparison comparison = new SalesComparison(
                    totals(3, "3000", "2700"), totals(0, "0", "0"));

            assertThat(comparison.gmvGrowthRate()).isNull();
            assertThat(comparison.netGrowthRate()).isNull();
            assertThat(comparison.countGrowthRate()).isNull();
        }

        @Test
        @DisplayName("양쪽 다 0 이어도 증감률은 없다 — 분모가 없다는 사실은 같다")
        void bothZero() {
            SalesComparison comparison = new SalesComparison(
                    totals(0, "0", "0"), totals(0, "0", "0"));

            assertThat(comparison.gmvGrowthRate()).isNull();
        }

        @Test
        @DisplayName("건수만 0 이면 건수 증감률만 없다 — 축마다 따로 판정한다")
        void perAxisIndependence() {
            SalesComparison comparison = new SalesComparison(
                    totals(5, "500", "450"), totals(0, "1000", "900"));

            assertThat(comparison.countGrowthRate()).isNull();
            assertThat(comparison.gmvGrowthRate()).isEqualByComparingTo("-0.5000");
        }
    }
}
