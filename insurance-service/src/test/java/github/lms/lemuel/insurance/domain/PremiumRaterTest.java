package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidProposalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보험료 산출 계산기 테스트 — 보험나이 6개월 경계와 원 단위 HALF_UP 라운딩이 핵심.
 */
@DisplayName("PremiumRater — 보험나이·연 보험료 산출")
class PremiumRaterTest {

    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 15);

    @Nested
    @DisplayName("보험나이 — 만 나이 + 마지막 생일 후 6개월 이상이면 +1")
    class InsuranceAge {

        @Test
        @DisplayName("생일 당일은 만 나이 그대로다")
        void onBirthday() {
            assertThat(PremiumRater.insuranceAge(BIRTH, LocalDate.of(2026, 1, 15))).isEqualTo(36);
        }

        @Test
        @DisplayName("6개월 하루 전까지는 만 나이 그대로다")
        void oneDayBeforeSixMonths() {
            assertThat(PremiumRater.insuranceAge(BIRTH, LocalDate.of(2026, 7, 14))).isEqualTo(36);
        }

        @Test
        @DisplayName("정확히 6개월 경과부터 +1 세다")
        void exactlySixMonths() {
            assertThat(PremiumRater.insuranceAge(BIRTH, LocalDate.of(2026, 7, 15))).isEqualTo(37);
        }

        @Test
        @DisplayName("출생 6개월 미만 신생아는 0세다")
        void newbornUnderSixMonths() {
            assertThat(PremiumRater.insuranceAge(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 8, 1))).isZero();
        }

        @Test
        @DisplayName("생년월일이 기준일보다 미래면 거부한다")
        void futureBirthDateRejected() {
            assertThatThrownBy(() -> PremiumRater.insuranceAge(
                    LocalDate.of(2027, 1, 1), LocalDate.of(2026, 8, 1)))
                    .isInstanceOf(InvalidProposalException.class);
        }
    }

    @Nested
    @DisplayName("연 보험료 — 보장금액 ÷ 1,000 × 요율, 원 단위 HALF_UP")
    class AnnualPremium {

        @Test
        @DisplayName("대표값: 보장 1억 × 요율 2.5 = 250,000원")
        void representativeCase() {
            assertThat(PremiumRater.annualPremium(new BigDecimal("100000000"), new BigDecimal("2.5")))
                    .isEqualByComparingTo("250000");
        }

        @Test
        @DisplayName("라운딩 경계 .5 는 올림이다 (999 × 1.5 = 1498.5 → 1499)")
        void halfRoundsUp() {
            assertThat(PremiumRater.annualPremium(new BigDecimal("999000"), new BigDecimal("1.5")))
                    .isEqualByComparingTo("1499");
        }

        @Test
        @DisplayName("라운딩 경계 .5 미만은 버림이다 (1498.4 → 1498)")
        void belowHalfRoundsDown() {
            // 998,933.333… × 1.5 / 1000 은 지저분하니 직접 .4 를 만드는 조합 사용
            assertThat(PremiumRater.annualPremium(new BigDecimal("999600"), new BigDecimal("1.499")))
                    .isEqualByComparingTo("1498"); // 999.6 × 1.499 = 1498.4004 → 1498
        }

        @Test
        @DisplayName("보장금액 0 이하는 거부한다")
        void nonPositiveCoverageRejected() {
            assertThatThrownBy(() -> PremiumRater.annualPremium(BigDecimal.ZERO, new BigDecimal("2.5")))
                    .isInstanceOf(InvalidProposalException.class);
            assertThatThrownBy(() -> PremiumRater.annualPremium(new BigDecimal("-1000"), new BigDecimal("2.5")))
                    .isInstanceOf(InvalidProposalException.class);
        }

        @Test
        @DisplayName("요율 0 이하는 거부한다")
        void nonPositiveRateRejected() {
            assertThatThrownBy(() -> PremiumRater.annualPremium(new BigDecimal("100000000"), BigDecimal.ZERO))
                    .isInstanceOf(InvalidProposalException.class);
        }

        @Test
        @DisplayName("라운딩 결과 0원 설계는 존재할 수 없다 — 임의 최소값으로 메꾸지 않는다")
        void zeroPremiumAfterRoundingRejected() {
            // 100 × 0.001 / 1000 = 0.0001 → HALF_UP(0) = 0 → 거부
            assertThatThrownBy(() -> PremiumRater.annualPremium(new BigDecimal("100"), new BigDecimal("0.001")))
                    .isInstanceOf(InvalidProposalException.class)
                    .hasMessageContaining("0원");
        }
    }
}
