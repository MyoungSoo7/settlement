package github.lms.lemuel.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구성비 — "결제수단/등급/상태별로 매출이 어떻게 갈리는가"를 계산한다.
 *
 * <p>정렬과 구성비를 도메인이 책임지는 이유: SQL 이 준 순서를 그대로 그리면 화면마다 순서가 달라지고,
 * 백분율을 화면이 계산하면 반올림 규칙이 화면 수만큼 생긴다.
 */
class SalesBreakdownTest {

    private static SalesSlice slice(String label, long count, String gmv) {
        return new SalesSlice(label, count, new BigDecimal(gmv),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(gmv));
    }

    @Nested
    @DisplayName("구성비")
    class Share {

        @Test
        @DisplayName("각 구간의 매출 비중을 백분율로 계산한다")
        void percentOfTotal() {
            SalesBreakdown breakdown = SalesBreakdown.from(List.of(
                    slice("CARD", 3, "7500"),
                    slice("TRANSFER", 1, "2500")));

            assertThat(breakdown.totalGmv()).isEqualByComparingTo("10000");
            assertThat(breakdown.totalTransactionCount()).isEqualTo(4);
            assertThat(breakdown.shares().get(0).sharePercent()).isEqualByComparingTo("75.00");
            assertThat(breakdown.shares().get(1).sharePercent()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("나누어떨어지지 않으면 소수 둘째 자리에서 반올림한다")
        void roundsToTwoDecimals() {
            SalesBreakdown breakdown = SalesBreakdown.from(List.of(
                    slice("A", 1, "1"),
                    slice("B", 1, "1"),
                    slice("C", 1, "1")));

            assertThat(breakdown.shares())
                    .allSatisfy(share -> assertThat(share.sharePercent()).isEqualByComparingTo("33.33"));
        }

        @Test
        @DisplayName("매출 합계가 0 이면 구성비도 0 이다 — 0 으로 나누지 않는다")
        void zeroTotalDoesNotDivide() {
            SalesBreakdown breakdown = SalesBreakdown.from(List.of(
                    slice("CARD", 2, "0"),
                    slice("POINT", 1, "0")));

            assertThat(breakdown.totalGmv()).isEqualByComparingTo("0");
            assertThat(breakdown.shares())
                    .allSatisfy(share -> assertThat(share.sharePercent()).isEqualByComparingTo("0"));
        }
    }

    @Nested
    @DisplayName("정렬")
    class Ordering {

        @Test
        @DisplayName("매출 큰 순으로 내려 정렬한다 — 화면은 위에서부터 읽는다")
        void sortedByGmvDesc() {
            SalesBreakdown breakdown = SalesBreakdown.from(List.of(
                    slice("SMALL", 1, "100"),
                    slice("BIG", 1, "900"),
                    slice("MID", 1, "500")));

            assertThat(breakdown.shares()).extracting(SalesShare::label)
                    .containsExactly("BIG", "MID", "SMALL");
        }
    }

    @Nested
    @DisplayName("경계")
    class Edges {

        @Test
        @DisplayName("빈 목록이면 비어 있는 구성이 나온다 — 거래가 없는 기간")
        void emptyInput() {
            SalesBreakdown breakdown = SalesBreakdown.from(List.of());

            assertThat(breakdown.shares()).isEmpty();
            assertThat(breakdown.totalGmv()).isEqualByComparingTo("0");
            assertThat(breakdown.totalTransactionCount()).isZero();
        }

        @Test
        @DisplayName("라벨이 비어 있으면 UNKNOWN 으로 묶는다 — payment_method 가 NULL 인 옛 행이 있다")
        void nullLabelBecomesUnknown() {
            SalesBreakdown breakdown = SalesBreakdown.from(List.of(slice(null, 1, "100")));

            assertThat(breakdown.shares().get(0).label()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("금액이 null 인 행도 0 으로 받아 넘긴다 — SUM 이 NULL 을 돌려주는 자리가 있다")
        void nullAmountsBecomeZero() {
            SalesSlice withNulls = new SalesSlice("CARD", 1, null, null, null, null);

            SalesBreakdown breakdown = SalesBreakdown.from(List.of(withNulls));

            assertThat(breakdown.shares().get(0).gmv()).isEqualByComparingTo("0");
            assertThat(breakdown.totalGmv()).isEqualByComparingTo("0");
        }
    }
}
