package github.lms.lemuel.settlement.domain.line;

import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 단위 금액(배송비·할인)을 라인에 비례 배분한다.
 *
 * <p><b>절대 불변식: 배분 합 == 원본 총액.</b> 라인별로 나눠 반올림하면 잔차가 생겨 합계가
 * 원본과 어긋나는데, 정산에서 이 1원이 곧 원장 불일치다. 그래서 최대잔여법(largest remainder)
 * 으로 정수 단위까지 배분하고 남은 단위를 소수부가 큰 라인부터 1원씩 얹는다 — 합이 항상 보존되고
 * 배분 왜곡도 최소가 된다.
 *
 * <p>ssg 는 배송비를 첫 상품행에 전액 몰아넣는다(합계는 맞지만 라인 마진이 왜곡된다).
 * 여기서는 비례 배분을 택하되 잔차 처리를 명시적으로 정의한다.
 */
class AmountAllocatorTest {

    private static List<BigDecimal> w(String... weights) {
        return java.util.Arrays.stream(weights).map(BigDecimal::new).toList();
    }

    @Test
    @DisplayName("나누어떨어지는 경우: 가중치 비율 그대로 배분")
    void exactDivision() {
        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("3000"), w("1000", "2000"));

        assertThat(result).containsExactly(new BigDecimal("1000"), new BigDecimal("2000"));
    }

    @Test
    @DisplayName("나누어떨어지지 않아도 배분 합은 원본과 정확히 일치한다 — 최대 불변식")
    void sumIsAlwaysPreserved() {
        // 1000 을 3등분: 333.33... → 333/333/334 로 합 1000 보존
        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("1000"), w("1", "1", "1"));

        assertThat(result.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1000");
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("잔차는 소수부가 큰 라인부터 1원씩 — 최대잔여법")
    void remainderGoesToLargestFraction() {
        // 총액 10, 가중치 3:3:4 → 정확값 3.0 / 3.0 / 4.0 (잔차 없음)
        assertThat(AmountAllocator.allocate(new BigDecimal("10"), w("3", "3", "4")))
                .containsExactly(new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("4"));

        // 총액 100, 가중치 1:1:1 → 33.33/33.33/33.33, 잔차 1 → 소수부 동률이면 앞 라인 우선
        List<BigDecimal> r = AmountAllocator.allocate(new BigDecimal("100"), w("1", "1", "1"));
        assertThat(r).containsExactly(new BigDecimal("34"), new BigDecimal("33"), new BigDecimal("33"));
        assertThat(r.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("가중치가 큰 라인이 더 많이 가져간다 — 비례성")
    void proportionalToWeight() {
        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("3000"), w("1000", "5000"));

        assertThat(result.get(0)).isLessThan(result.get(1));
        assertThat(result.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("배분액 0원: 배송비 무료면 전 라인 0")
    void zeroTotalAllocatesZero() {
        assertThat(AmountAllocator.allocate(BigDecimal.ZERO, w("1000", "2000")))
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("경계: 1원은 가중치가 가장 큰 라인 하나에만 간다")
    void oneWonGoesToSingleLine() {
        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("1"), w("100", "900"));

        assertThat(result.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1");
        assertThat(result.get(1)).isEqualByComparingTo("1");
        assertThat(result.get(0)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("단일 라인이면 전액이 그 라인으로")
    void singleLineTakesAll() {
        assertThat(AmountAllocator.allocate(new BigDecimal("2500"), w("1000")))
                .containsExactly(new BigDecimal("2500"));
    }

    @Test
    @DisplayName("가중치 총합이 0이면 균등 배분 — 0원 상품만 있는 주문에서도 합은 보존된다")
    void zeroWeightFallsBackToEven() {
        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("10"), w("0", "0", "0"));

        assertThat(result.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("음수 총액(할인 환원 등)도 합이 보존된다")
    void negativeTotalPreservesSum() {
        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("-1000"), w("1", "1", "1"));

        assertThat(result.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-1000");
    }

    @Test
    @DisplayName("빈 라인 목록은 거부 — 배분할 대상이 없다")
    void rejectsEmptyWeights() {
        assertThatThrownBy(() -> AmountAllocator.allocate(new BigDecimal("100"), List.of()))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("음수 가중치는 거부 — 상품 금액은 음수일 수 없다")
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> AmountAllocator.allocate(new BigDecimal("100"), w("1000", "-1")))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("총액 null 은 거부 — 0 과 '모름'을 섞지 않는다")
    void rejectsNullTotal() {
        assertThatThrownBy(() -> AmountAllocator.allocate(null, w("1000")))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }

    @Test
    @DisplayName("대량 라인에서도 합이 보존된다 — 잔차 누적 검증")
    void sumPreservedAcrossManyLines() {
        List<BigDecimal> weights = java.util.stream.IntStream.rangeClosed(1, 97)
                .mapToObj(i -> new BigDecimal(i * 13))
                .toList();

        List<BigDecimal> result = AmountAllocator.allocate(new BigDecimal("100003"), weights);

        assertThat(result).hasSize(97);
        assertThat(result.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100003");
    }
}
