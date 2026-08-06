package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidClawbackInputException;
import github.lms.lemuel.insurance.domain.exception.InvalidClawbackStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D6 — ClawbackCalculator 테스트.
 *
 * <p>환수(clawback) 공식 검증:
 * <ul>
 *   <li>m = 효력일부터 종료일까지 완료된 개월 수 (ChronoUnit.MONTHS.between, 내림)</li>
 *   <li>m >= 24 → 환수액 0</li>
 *   <li>m < 24 → 환수액 = 기지급 합계 × (24 - m) / 24, 통화 최소단위 절사(RoundingPolicy 준수)</li>
 *   <li>CANCELLED 상태 → m 무관 전액 환수</li>
 *   <li>환수 트리거 상태: SURRENDERED, CANCELLED, LAPSED→EXPIRED</li>
 * </ul>
 *
 * <p><b>D6 상수 규율</b>: CLAWBACK_WINDOW_MONTHS = 24 는 한 곳에서만 선언.
 * 비-테스트 코드의 매직넘버 "24" 는 0건.
 */
@DisplayName("ClawbackCalculator (D6 환수 계산)")
class ClawbackCalculatorTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 1);
    private static final BigDecimal PAID_TOTAL = new BigDecimal("1200.00");

    /**
     * D6: m=0 (계약 직후 종료) → 전액 환수.
     * 환수액 = 1200 × (24-0)/24 = 1200
     */
    @Test
    @DisplayName("D6-1: m=0 → 환수액 = 전액 (1200.00)")
    void clawback_m0_full() {
        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                EFFECTIVE,
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    /**
     * D6: m=3 (3개월 경과) → 환수액 = 1200 × (24-3)/24 = 1200 × 21/24 = 1050.00.
     */
    @Test
    @DisplayName("D6-2: m=3 → 환수액 = 1200 × 21/24 = 1050.00")
    void clawback_m3() {
        LocalDate endDate = EFFECTIVE.plusMonths(3);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(new BigDecimal("1050.00"));
    }

    /**
     * D6: m=23 (23개월 경과) → 환수액 = 1200 × (24-23)/24 = 1200 × 1/24 = 50.00.
     */
    @Test
    @DisplayName("D6-3: m=23 → 환수액 = 1200 × 1/24 = 50.00")
    void clawback_m23() {
        LocalDate endDate = EFFECTIVE.plusMonths(23);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    /**
     * D6: m=24 (정확히 24개월) → 환수액 = 0 (환수 창구 종료).
     */
    @Test
    @DisplayName("D6-4: m=24 → 환수액 = 0 (창구 종료)")
    void clawback_m24_zero() {
        LocalDate endDate = EFFECTIVE.plusMonths(24);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * D6: m>24 (24개월 초과) → 환수액 = 0 (환수 창구 종료).
     */
    @Test
    @DisplayName("D6-5: m=30 → 환수액 = 0 (창구 종료)")
    void clawback_m30_zero() {
        LocalDate endDate = EFFECTIVE.plusMonths(30);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * D6: CANCELLED 상태 → m 무관 전액 환수 (m=0).
     */
    @Test
    @DisplayName("D6-6: CANCELLED 상태 → 전액 환수 (m 무관)")
    void clawback_cancelled_fullAmount() {
        // 계약 2년 후 취소되어도 전액 환수
        LocalDate endDate = EFFECTIVE.plusYears(3);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.CANCELLED);

        assertThat(result).isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    /**
     * D6: LAPSED→EXPIRED 소멸 상태에서 m=23 → 환수액 = 1200 × 1/24.
     */
    @Test
    @DisplayName("D6-7: LAPSED→EXPIRED 소멸, m=23 → 1200 × 1/24 = 50.00")
    void clawback_expired_after_lapse_m23() {
        LocalDate endDate = EFFECTIVE.plusMonths(23);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.EXPIRED);

        assertThat(result).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    /**
     * D6: 0개월은 환수 대상이 아닌 상태 → 예외.
     */
    @Test
    @DisplayName("D6-8: 환수 불가 상태(ACTIVE) → 예외")
    void clawback_invalid_status_throws() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                EFFECTIVE,
                PolicyStatus.ACTIVE))
                .isInstanceOf(InvalidClawbackStateException.class)
                .hasMessageContaining("환수 대상이 아닌 상태");
    }

    /**
     * D6: 환수 불가 상태(LAPSED) → 예외.
     * LAPSED 는 트리거가 아니라, LAPSED→EXPIRED 전이 후에만 환수.
     */
    @Test
    @DisplayName("D6-9: LAPSED 상태(미소멸) → 환수 불가")
    void clawback_lapsed_not_triggered() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                EFFECTIVE.plusMonths(10),
                PolicyStatus.LAPSED))
                .isInstanceOf(InvalidClawbackStateException.class)
                .hasMessageContaining("환수 대상이 아닌 상태");
    }

    /**
     * D6: RoundingPolicy 준수 — 절사(내림).
     * 1200 × 7/24 = 350.0 (정확)
     * 1000 × 7/24 = 291.666... → 291.66 (절사)
     */
    @Test
    @DisplayName("D6-10: RoundingPolicy 절사 — 1000 × 7/24 = 291.66")
    void clawback_rounding_truncate() {
        BigDecimal paidTotal = new BigDecimal("1000.00");
        LocalDate endDate = EFFECTIVE.plusMonths(7);

        BigDecimal result = ClawbackCalculator.calculate(
                paidTotal,
                EFFECTIVE,
                endDate,
                PolicyStatus.SURRENDERED);

        // 1000 × (24-7)/24 = 1000 × 17/24 = 708.33...
        // DOWN 절사 → 708.33
        assertThat(result).isEqualByComparingTo(new BigDecimal("708.33"));
    }

    /**
     * D6: 경계 케이스 — m=1 (1개월 경과) → 환수액 = 1200 × 23/24 = 1150.00.
     */
    @Test
    @DisplayName("D6-11: 경계 m=1 → 환수액 = 1200 × 23/24 = 1150.00")
    void clawback_m1_boundary() {
        LocalDate endDate = EFFECTIVE.plusMonths(1);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(new BigDecimal("1150.00"));
    }

    /**
     * D6: 환수 트리거 상태 검증 — SURRENDERED, CANCELLED, EXPIRED(소멸) 만 허용.
     */
    @ParameterizedTest(name = "{0} 상태에서 환수 계산")
    @ValueSource(strings = {"SURRENDERED", "CANCELLED", "EXPIRED"})
    @DisplayName("D6-12: 허용 상태에서 환수 계산 성공")
    void clawback_valid_statuses(String statusStr) {
        PolicyStatus status = PolicyStatus.valueOf(statusStr);
        LocalDate endDate = EFFECTIVE.plusMonths(12);

        BigDecimal result = ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                status);

        assertThat(result).isNotNull();
        assertThat(result).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    /**
     * D6: 환수 불가 상태 검증 — ACTIVE, LAPSED(미소멸) 는 불허용.
     */
    @ParameterizedTest(name = "{0} 상태에서 환수 계산 불가")
    @ValueSource(strings = {"ACTIVE", "LAPSED"})
    @DisplayName("D6-13: 환수 불가 상태에서 예외")
    void clawback_invalid_statuses(String statusStr) {
        PolicyStatus status = PolicyStatus.valueOf(statusStr);
        LocalDate endDate = EFFECTIVE.plusMonths(12);

        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                endDate,
                status))
                .isInstanceOf(InvalidClawbackStateException.class);
    }

    /**
     * D6: null 입력값 검증.
     */
    @Test
    @DisplayName("D6-14: null paidTotal → NPE")
    void clawback_null_paidTotal() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                null,
                EFFECTIVE,
                EFFECTIVE,
                PolicyStatus.SURRENDERED))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("D6-15: null effectiveDate → NPE")
    void clawback_null_effectiveDate() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                null,
                EFFECTIVE,
                PolicyStatus.SURRENDERED))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("D6-16: null endDate → NPE")
    void clawback_null_endDate() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                null,
                PolicyStatus.SURRENDERED))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("D6-17: null status → NPE")
    void clawback_null_status() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                EFFECTIVE,
                null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("D6-18: 음수 기지급 합계는 거부")
    void clawback_negativePaidTotal_throws() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                new BigDecimal("-0.01"),
                EFFECTIVE,
                EFFECTIVE,
                PolicyStatus.SURRENDERED))
                .isInstanceOf(InvalidClawbackInputException.class)
                .hasMessageContaining("paidTotal");
    }

    @Test
    @DisplayName("D6-19: 0 기지급 합계는 경계값으로 허용")
    void clawback_zeroPaidTotal_isAllowed() {
        BigDecimal result = ClawbackCalculator.calculate(
                BigDecimal.ZERO,
                EFFECTIVE,
                EFFECTIVE.plusMonths(1),
                PolicyStatus.SURRENDERED);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("D6-20: 종료일이 효력일보다 빠르면 CANCELLED도 거부")
    void clawback_endDateBeforeEffectiveDate_throwsForCancelled() {
        assertThatThrownBy(() -> ClawbackCalculator.calculate(
                PAID_TOTAL,
                EFFECTIVE,
                EFFECTIVE.minusDays(1),
                PolicyStatus.CANCELLED))
                .isInstanceOf(InvalidClawbackInputException.class)
                .hasMessageContaining("endDate");
    }

    /**
     * D6 상수 규율 검증: CLAWBACK_WINDOW_MONTHS 를 소비하고 있음을 확인.
     */
    @Test
    @DisplayName("D6-상수: CommissionConstants.CLAWBACK_WINDOW_MONTHS 는 24")
    void constant_clawback_window_months() {
        assertThat(CommissionConstants.CLAWBACK_WINDOW_MONTHS).isEqualTo(24);
    }

    /**
     * D6 다중 환수 시나리오 — 여러 지급 행에 대한 환수 합계.
     * 이는 서비스 레이어 책임이지만, calculator 자체는 단일 행 기준.
     */
    @Test
    @DisplayName("D6-21: 다중 스케줄 환수 합계 시뮬레이션")
    void clawback_multi_schedule_total() {
        List<BigDecimal> paidAmounts = Arrays.asList(
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"));
        LocalDate endDate = EFFECTIVE.plusMonths(12);

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : paidAmounts) {
            total = total.add(ClawbackCalculator.calculate(
                    amount,
                    EFFECTIVE,
                    endDate,
                    PolicyStatus.SURRENDERED));
        }

        // 각 100 × 12/24 = 50, 총 3행 = 150
        assertThat(total).isEqualByComparingTo(new BigDecimal("150.00"));
    }
}
