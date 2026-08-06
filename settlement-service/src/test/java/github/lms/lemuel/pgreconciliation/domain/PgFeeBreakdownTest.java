package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG 공제 항목 분해 — 실입금 검증의 기초.
 *
 * <p>PG 정산파일은 수수료를 단일 금액으로 주지 않는다. 기본 수수료·부가세·에스크로·이체수수료가
 * 각각 따로 찍히고, 이 합이 매출에서 빠져 실입금이 된다. 항목을 뭉개면 "왜 100원이 덜 들어왔는지"를
 * 사후에 추적할 수 없다.
 */
class PgFeeBreakdownTest {

    @Test
    @DisplayName("총 공제액은 항목별 합산 — 총액에 요율을 곱하지 않는다")
    void totalDeductionSumsItems() {
        PgFeeBreakdown fees = PgFeeBreakdown.of(
                new BigDecimal("300"),   // pgFee
                new BigDecimal("30"),    // pgFeeVat
                new BigDecimal("100"),   // escrowFee
                new BigDecimal("10"),    // escrowVat
                new BigDecimal("50"),    // transferFee
                new BigDecimal("5"),     // transferVat
                new BigDecimal("20"));   // additionalFee

        assertThat(fees.totalDeduction()).isEqualByComparingTo("515");
    }

    @Test
    @DisplayName("null 항목은 0 으로 흡수 — PG 마다 없는 항목이 있다")
    void nullsBecomeZero() {
        PgFeeBreakdown fees = PgFeeBreakdown.of(
                new BigDecimal("300"), null, null, null, null, null, null);

        assertThat(fees.pgFeeVat()).isEqualByComparingTo("0");
        assertThat(fees.escrowFee()).isEqualByComparingTo("0");
        assertThat(fees.totalDeduction()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("공제 없음: 모든 항목 0")
    void noneIsAllZero() {
        assertThat(PgFeeBreakdown.none().totalDeduction()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("레거시 단일 수수료는 pgFee 로 매핑 — 분해 이전 파일 하위호환")
    void legacySingleFeeMapsToPgFee() {
        PgFeeBreakdown fees = PgFeeBreakdown.legacy(new BigDecimal("750"));

        assertThat(fees.pgFee()).isEqualByComparingTo("750");
        assertThat(fees.totalDeduction()).isEqualByComparingTo("750");
        assertThat(fees.isDecomposed()).isFalse();
    }

    @Test
    @DisplayName("분해 여부 판정 — 기본 수수료 외 항목이 하나라도 있으면 분해된 것")
    void isDecomposed() {
        assertThat(PgFeeBreakdown.legacy(new BigDecimal("750")).isDecomposed()).isFalse();
        assertThat(PgFeeBreakdown.none().isDecomposed()).isFalse();
        assertThat(PgFeeBreakdown.of(new BigDecimal("300"), new BigDecimal("30"),
                null, null, null, null, null).isDecomposed()).isTrue();
    }

    @Test
    @DisplayName("음수 공제는 거부 — 공제는 양수로 표현하고 부호는 사용처가 정한다")
    void rejectsNegativeItem() {
        assertThatThrownBy(() -> PgFeeBreakdown.of(
                new BigDecimal("-1"), null, null, null, null, null, null))
                .isInstanceOf(PgReconciliationInvariantViolationException.class)
                .hasMessageContaining("pgFee");
    }

    @Test
    @DisplayName("경계: 0원 공제는 허용 — 프로모션 무수수료 거래")
    void allowsZeroFee() {
        PgFeeBreakdown fees = PgFeeBreakdown.of(BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null);

        assertThat(fees.totalDeduction()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("소수 공제도 정확히 합산 — 이체수수료는 원 미만이 나올 수 있다")
    void sumsFractionalAmounts() {
        PgFeeBreakdown fees = PgFeeBreakdown.of(
                new BigDecimal("300.55"), new BigDecimal("30.05"),
                null, null, new BigDecimal("0.40"), null, null);

        assertThat(fees.totalDeduction()).isEqualByComparingTo("331.00");
    }

    @Test
    @DisplayName("부가세 합계는 별도로 뽑을 수 있다 — 세무 대사에서 매입세액으로 쓰인다")
    void vatTotalIsSeparable() {
        PgFeeBreakdown fees = PgFeeBreakdown.of(
                new BigDecimal("300"), new BigDecimal("30"),
                new BigDecimal("100"), new BigDecimal("10"),
                new BigDecimal("50"), new BigDecimal("5"),
                new BigDecimal("20"));

        assertThat(fees.totalVat()).isEqualByComparingTo("45");
    }
}
