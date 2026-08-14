package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-G2·D-G3 산출 검증 — 기납입보험료(정상납입 가정)·해약환급률 구간 경계·라운딩(DOWN)·
 * 만기 전일 산입·철회 전액.
 */
@DisplayName("일반지급 산출 (D-G2 기납입 근사 · D-G3 환급률표)")
class GeneralPayoutCalculatorTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2020, 1, 1);
    private static final BigDecimal ANNUAL_1_2M = new BigDecimal("1200000.00");

    // ────────────────────────────────────────────────────────────────────
    // 해약환급금
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("해약환급금 (SURRENDER_REFUND)")
    class SurrenderRefund {

        @Test
        @DisplayName("월납 36개월 경과 해지: 37회 납입 × 60% 구간 요율")
        void surrenderAtThirtySixMonths() {
            // 회차보험료 100,000 × 37회(효력일 회차 포함) = 3,700,000 → 60% = 2,220,000
            PayoutQuote quote = GeneralPayoutCalculator.surrenderRefund(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE.plusMonths(36));

            assertThat(quote.paidPremiumTotal()).isEqualByComparingTo("3700000.00");
            assertThat(quote.appliedRate()).isEqualByComparingTo("0.60");
            assertThat(quote.amount()).isEqualByComparingTo("2220000.00");
            assertThat(quote.elapsedMonths()).isEqualTo(36);
            assertThat(quote.installmentCount()).isEqualTo(37);
        }

        @ParameterizedTest(name = "경과 {0}개월 → 요율 {1}")
        @DisplayName("환급률표 구간 경계 — 하한 포함, 상한 미포함")
        @CsvSource({
                "0,   0.00",
                "11,  0.00",
                "12,  0.40",
                "35,  0.40",
                "36,  0.60",
                "59,  0.60",
                "60,  0.75",
                "83,  0.75",
                "84,  0.85",
                "200, 0.85",
        })
        void tierBoundaries(long elapsedMonths, String expectedRate) {
            PayoutQuote quote = GeneralPayoutCalculator.surrenderRefund(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE.plusMonths(elapsedMonths));

            assertThat(quote.appliedRate()).isEqualByComparingTo(expectedRate);
        }

        @Test
        @DisplayName("12개월 미만 해지는 환급액 0 — 근거(기납입)는 그대로 남는다")
        void zeroRefundBeforeTwelveMonths() {
            PayoutQuote quote = GeneralPayoutCalculator.surrenderRefund(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE.plusMonths(11));

            assertThat(quote.amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(quote.paidPremiumTotal()).isEqualByComparingTo("1200000.00"); // 12회
        }

        @Test
        @DisplayName("환급액은 통화 최소단위 절사(DOWN) — 환수 계산과 같은 정책")
        void refundIsFlooredToAmountScale() {
            // 연납 3,333.33 × 2회 = 6,666.66 → × 40% = 2,666.664 → DOWN → 2,666.66
            PayoutQuote quote = GeneralPayoutCalculator.surrenderRefund(
                    new BigDecimal("3333.33"), 12, EFFECTIVE, EFFECTIVE.plusMonths(17));

            assertThat(quote.paidPremiumTotal()).isEqualByComparingTo("6666.66");
            assertThat(quote.amount()).isEqualByComparingTo("2666.66");
        }

        @Test
        @DisplayName("분기납: 회차보험료는 연 보험료 × 3/12 절사, 회차수는 floor(경과월/3)+1")
        void quarterlyCycle() {
            // 회차보험료 = 1000.01 × 3 / 12 = 250.0025 → DOWN → 250.00
            // 경과 13개월 → floor(13/3)+1 = 5회 → 1,250.00 → × 40% = 500.00
            PayoutQuote quote = GeneralPayoutCalculator.surrenderRefund(
                    new BigDecimal("1000.01"), 3, EFFECTIVE, EFFECTIVE.plusMonths(13));

            assertThat(quote.paidPremiumTotal()).isEqualByComparingTo("1250.00");
            assertThat(quote.installmentCount()).isEqualTo(5);
            assertThat(quote.amount()).isEqualByComparingTo("500.00");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 만기보험금
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("만기보험금 (MATURITY_BENEFIT)")
    class MaturityBenefit {

        @Test
        @DisplayName("10년 만기 월납: 만기 전일까지 정확히 120회 산입 — 기납입 100%")
        void tenYearMonthlyTerm() {
            LocalDate maturity = EFFECTIVE.plusYears(10);

            PayoutQuote quote = GeneralPayoutCalculator.maturityBenefit(
                    ANNUAL_1_2M, 1, EFFECTIVE, maturity);

            // 만기일 당일 회차는 없다: 2029-12-01 이 마지막 회차(120번째)
            assertThat(quote.installmentCount()).isEqualTo(120);
            assertThat(quote.paidPremiumTotal()).isEqualByComparingTo("12000000.00");
            assertThat(quote.amount()).isEqualByComparingTo("12000000.00");
            assertThat(quote.appliedRate()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(quote.elapsedMonths()).isEqualTo(120);
        }

        @Test
        @DisplayName("연납 5년 만기: 5회 산입")
        void fiveYearAnnualTerm() {
            PayoutQuote quote = GeneralPayoutCalculator.maturityBenefit(
                    ANNUAL_1_2M, 12, EFFECTIVE, EFFECTIVE.plusYears(5));

            assertThat(quote.installmentCount()).isEqualTo(5);
            assertThat(quote.amount()).isEqualByComparingTo("6000000.00");
        }

        @Test
        @DisplayName("만기일이 효력일과 같거나 빠르면 산출 거부")
        void rejectsNonPositiveTerm() {
            assertThatThrownBy(() -> GeneralPayoutCalculator.maturityBenefit(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE))
                    .isInstanceOf(InvalidGeneralPayoutException.class);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 철회환급금
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("철회환급금 (WITHDRAWAL_REFUND)")
    class WithdrawalRefund {

        @Test
        @DisplayName("철회는 기납입 전액 — 15일 창구 내 월납 1회분")
        void refundsFullPaidPremium() {
            PayoutQuote quote = GeneralPayoutCalculator.withdrawalRefund(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE.plusDays(9));

            assertThat(quote.installmentCount()).isEqualTo(1);
            assertThat(quote.amount()).isEqualByComparingTo("100000.00");
            assertThat(quote.appliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("효력일 당일 철회도 1회차(효력일 납입분)는 환급된다")
        void refundsFirstInstallmentOnDayZero() {
            PayoutQuote quote = GeneralPayoutCalculator.withdrawalRefund(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE);

            assertThat(quote.installmentCount()).isEqualTo(1);
            assertThat(quote.amount()).isEqualByComparingTo("100000.00");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 입력 검증
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("입력 검증")
    class InputValidation {

        @Test
        @DisplayName("음수 보험료 거부")
        void rejectsNegativePremium() {
            assertThatThrownBy(() -> GeneralPayoutCalculator.surrenderRefund(
                    new BigDecimal("-1.00"), 1, EFFECTIVE, EFFECTIVE.plusMonths(12)))
                    .isInstanceOf(InvalidGeneralPayoutException.class);
        }

        @Test
        @DisplayName("기준일이 효력일보다 빠르면 거부")
        void rejectsEndBeforeEffective() {
            assertThatThrownBy(() -> GeneralPayoutCalculator.surrenderRefund(
                    ANNUAL_1_2M, 1, EFFECTIVE, EFFECTIVE.minusDays(1)))
                    .isInstanceOf(InvalidGeneralPayoutException.class);
        }

        @ParameterizedTest(name = "납입주기 {0}개월 거부")
        @DisplayName("12를 나누지 못하는 납입주기 거부")
        @CsvSource({"0", "-1", "5", "7", "13"})
        void rejectsInvalidCycle(int cycleMonths) {
            assertThatThrownBy(() -> GeneralPayoutCalculator.surrenderRefund(
                    ANNUAL_1_2M, cycleMonths, EFFECTIVE, EFFECTIVE.plusMonths(12)))
                    .isInstanceOf(InvalidGeneralPayoutException.class);
        }

        @Test
        @DisplayName("null 입력은 NPE — 조용한 기본값 없음")
        void rejectsNulls() {
            assertThatThrownBy(() -> GeneralPayoutCalculator.surrenderRefund(
                    null, 1, EFFECTIVE, EFFECTIVE.plusMonths(12)))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
