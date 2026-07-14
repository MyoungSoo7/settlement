package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorporateCreditPolicyTest {

    private final CorporateCreditPolicy policy =
            new CorporateCreditPolicy(new BigDecimal("0.0002"), new BigDecimal("0.10"));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // ─── 안정성(부채비율) 40점 구간 경계 ─────────────────────────────────────────

    @Nested
    class 안정성점수 {
        @Test void 자본잠식_null은_0점() { assertThat(policy.stabilityScore(null)).isZero(); }

        @ParameterizedTest
        @CsvSource({
                "0, 40", "100, 40",       // ≤100 → 40
                "100.01, 30", "200, 30",  // ≤200 → 30
                "200.01, 20", "300, 20",  // ≤300 → 20
                "300.01, 10", "400, 10",  // ≤400 → 10
                "400.01, 0", "999, 0"     // 초과 → 0
        })
        void 부채비율구간(String debtRatio, int expected) {
            assertThat(policy.stabilityScore(bd(debtRatio))).isEqualTo(expected);
        }
    }

    // ─── 수익성(영업이익률 + ROA) 40점 ───────────────────────────────────────────

    @Nested
    class 수익성점수 {
        @Test void null은_각_0점() {
            assertThat(policy.operatingMarginScore(null)).isZero();
            assertThat(policy.roaScore(null)).isZero();
            assertThat(policy.profitabilityScore(null, null)).isZero();
        }

        @ParameterizedTest
        @CsvSource({
                "-1, 0", "0, 5", "4.99, 5", "5, 10", "9.99, 10", "10, 15", "19.99, 15", "20, 20", "50, 20"
        })
        void 영업이익률구간(String margin, int expected) {
            assertThat(policy.operatingMarginScore(bd(margin))).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "-1, 0", "0, 5", "1.99, 5", "2, 10", "4.99, 10", "5, 15", "9.99, 15", "10, 20", "30, 20"
        })
        void ROA구간(String roa, int expected) {
            assertThat(policy.roaScore(bd(roa))).isEqualTo(expected);
        }

        @Test void 합산은_두_구간의_합() {
            // opMargin 20(=20) + roa 5(=15) = 35
            assertThat(policy.profitabilityScore(bd("20"), bd("5"))).isEqualTo(35);
        }
    }

    // ─── 평판 20점 ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({ "A, 20", "B, 15", "C, 10", "D, 5", "E, 0", "Z, 10" })
    void 평판점수(String grade, int expected) {
        assertThat(policy.reputationScore(grade)).isEqualTo(expected);
    }

    @Test
    void 평판미상_null은_중립10점() {
        assertThat(policy.reputationScore(null)).isEqualTo(10);
    }

    // ─── 신용점수 합산 + 등급 경계 ───────────────────────────────────────────────

    @Test
    void 신용점수는_세축의_합() {
        // 부채비율 90(안정성40) + opMargin 20(20) + roa 10(20) + 평판A(20) = 100
        CorporateFinancials fin = new CorporateFinancials("005930", "삼성전자", "KOSPI", 2024,
                bd("90"), bd("20"), bd("10"), bd("300000000"), bd("50000000"));
        assertThat(policy.creditScore(fin, "A")).isEqualTo(100);
    }

    @Test
    void 자본잠식_전축결측이면_평판만_반영() {
        // debtRatio null(0) + opMargin null(0) + roa null(0) + 평판 null(10) = 10
        CorporateFinancials fin = new CorporateFinancials("999999", "부실기업", "KOSDAQ", 2024,
                null, null, null, null, bd("-100"));
        assertThat(policy.creditScore(fin, null)).isEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({
            "100, A", "80, A", "79, B", "65, B", "64, C", "50, C", "49, D", "35, D", "34, E", "0, E"
    })
    void 등급경계(int score, String grade) {
        assertThat(policy.creditGrade(score)).isEqualTo(grade);
    }

    @Test
    void E등급만_대출불가() {
        assertThat(policy.isLoanBlocked("E")).isTrue();
        assertThat(policy.isLoanBlocked("D")).isFalse();
        assertThat(policy.isLoanBlocked("A")).isFalse();
    }

    // ─── 한도 ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "A, 100000.00", "B, 80000.00", "C, 60000.00", "D, 30000.00", "E, 0.00"
    })
    void 한도는_자본총계의_10퍼센트에_등급계수(String grade, String expected) {
        // 자본총계 1,000,000 × 0.10 × gradeFactor
        assertThat(policy.creditLimit(bd("1000000"), grade)).isEqualByComparingTo(expected);
    }

    @Test
    void 자본총계_null이거나_0이하면_한도_0() {
        assertThat(policy.creditLimit(null, "A")).isEqualByComparingTo("0");
        assertThat(policy.creditLimit(BigDecimal.ZERO, "A")).isEqualByComparingTo("0");
        assertThat(policy.creditLimit(bd("-100"), "A")).isEqualByComparingTo("0");
    }

    // ─── 수수료 ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            // 1,000,000 × 0.0002 × 30 = 6000, × 등급가산
            "A, 6000.00", "B, 6600.00", "C, 7500.00", "D, 9000.00"
    })
    void 수수료는_원금_일할이율_기간_등급가산(String grade, String expected) {
        assertThat(policy.fee(bd("1000000"), 30, grade)).isEqualByComparingTo(expected);
    }

    @Test
    void 기간이_0이면_수수료_0() {
        assertThat(policy.fee(bd("1000000"), 0, "A")).isEqualByComparingTo("0");
    }

    @Test
    void 기간이_음수면_예외() {
        assertThatThrownBy(() -> policy.fee(bd("1000000"), -1, "A"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Nested
    class 등급계수_직접 {
        @ParameterizedTest
        @ValueSource(strings = { "A", "B", "C", "D", "E" })
        void 계수는_양수또는0(String grade) {
            assertThat(policy.gradeFactor(grade)).isNotNull();
            assertThat(policy.gradeSurcharge(grade)).isNotNull();
        }

        @ParameterizedTest
        @NullSource
        void null등급이면_점수는_중립(String grade) {
            assertThat(policy.reputationScore(grade)).isEqualTo(10);
        }
    }
}
