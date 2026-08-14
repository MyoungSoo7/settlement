package github.lms.lemuel.account.banking.savings.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적금 이자 계산기 단위 테스트.
 *
 * <p>검증이 쉽도록 이율은 0.0365 를 쓴다 — {@code 0.0365 / 365 = 0.0001} 이라
 * "이자 = 납입액 × 0.0001 × 예치일수" 가 되어 기대값을 손으로 계산할 수 있다.
 */
class InstallmentSavingsInterestTest {

    private static final BigDecimal RATE = new BigDecimal("0.0365");   // 일당 0.0001

    private static SavingsInstallment installment(int round, String amount, LocalDate paidOn) {
        return SavingsInstallment.reconstitute(null, round, new BigDecimal(amount),
                paidOn, paidOn, 0);
    }

    @Test
    void 단일_회차는_예치일수만큼_단리이자를_받는다() {
        // 2026-01-01 → 2026-04-11 = 100일, 1,000,000 × 0.0001 × 100 = 10,000
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "1000000", LocalDate.of(2026, 1, 1))),
                RATE, LocalDate.of(2026, 4, 11));

        assertThat(interest).isEqualByComparingTo("10000");
    }

    @Test
    void 회차마다_예치일수가_달라_이자가_가중된다() {
        // 1회차 100일 → 10,000 / 2회차 69일 → 6,900
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "1000000", LocalDate.of(2026, 1, 1)),
                        installment(2, "1000000", LocalDate.of(2026, 2, 1))),
                RATE, LocalDate.of(2026, 4, 11));

        assertThat(interest).isEqualByComparingTo("16900");
    }

    @Test
    void 늦게_납입한_회차는_예치일수가_줄어_이자가_적다() {
        BigDecimal onTime = InstallmentSavingsInterest.calculate(
                List.of(installment(2, "1000000", LocalDate.of(2026, 2, 1))),
                RATE, LocalDate.of(2026, 4, 11));
        BigDecimal late = InstallmentSavingsInterest.calculate(
                List.of(installment(2, "1000000", LocalDate.of(2026, 2, 11))),
                RATE, LocalDate.of(2026, 4, 11));

        // 10일 늦으면 1,000,000 × 0.0001 × 10 = 1,000 만큼 줄어든다
        assertThat(onTime.subtract(late)).isEqualByComparingTo("1000");
    }

    @Test
    void 반올림은_회차별이_아니라_합계에_한_번만_적용된다() {
        // 회차별 이자가 각각 0.5 원 — 회차마다 반올림하면 1+1=2, 합계에 한 번이면 1.0 → 1
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "5000", LocalDate.of(2026, 1, 1)),
                        installment(2, "5000", LocalDate.of(2026, 1, 1))),
                RATE, LocalDate.of(2026, 1, 2));

        assertThat(interest).isEqualByComparingTo("1");
    }

    @Test
    void 원_단위_반올림은_HALF_UP_이다() {
        // 105,000 × 0.0001 × 1일 = 10.5 → 11
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "105000", LocalDate.of(2026, 1, 1))),
                RATE, LocalDate.of(2026, 1, 2));

        assertThat(interest).isEqualByComparingTo("11");
    }

    @Test
    void 이자는_항상_원_단위로_반환된다() {
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "1000000", LocalDate.of(2026, 1, 1))),
                RATE, LocalDate.of(2026, 4, 11));

        assertThat(interest.scale()).isZero();
    }

    @Test
    void 종료일_당일_납입은_예치일수가_0_이라_이자가_없다() {
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "1000000", LocalDate.of(2026, 4, 11))),
                RATE, LocalDate.of(2026, 4, 11));

        assertThat(interest).isEqualByComparingTo("0");
    }

    @Test
    void 종료일_이후_납입도_음수이자가_되지_않는다() {
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "1000000", LocalDate.of(2026, 5, 1))),
                RATE, LocalDate.of(2026, 4, 11));

        assertThat(interest).isEqualByComparingTo("0");
    }

    @Test
    void 회차가_없거나_이율이_0_이면_이자는_0() {
        LocalDate end = LocalDate.of(2026, 4, 11);
        List<SavingsInstallment> one = List.of(installment(1, "1000000", LocalDate.of(2026, 1, 1)));

        assertThat(InstallmentSavingsInterest.calculate(List.of(), RATE, end)).isEqualByComparingTo("0");
        assertThat(InstallmentSavingsInterest.calculate(null, RATE, end)).isEqualByComparingTo("0");
        assertThat(InstallmentSavingsInterest.calculate(one, BigDecimal.ZERO, end)).isEqualByComparingTo("0");
        assertThat(InstallmentSavingsInterest.calculate(one, null, end)).isEqualByComparingTo("0");
        assertThat(InstallmentSavingsInterest.calculate(one, RATE, null)).isEqualByComparingTo("0");
    }

    @Test
    void 예치일수는_음수로_내려가지_않는다() {
        assertThat(InstallmentSavingsInterest.daysHeld(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 11))).isEqualTo(10L);
        assertThat(InstallmentSavingsInterest.daysHeld(
                LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 1))).isZero();
        assertThat(InstallmentSavingsInterest.daysHeld(null, LocalDate.of(2026, 1, 1))).isZero();
        assertThat(InstallmentSavingsInterest.daysHeld(LocalDate.of(2026, 1, 1), null)).isZero();
    }

    @Test
    void 나눗셈은_무한소수에서도_터지지_않는다() {
        // 1/3 처럼 무한소수가 되는 조합 — scale 미지정 divide 였다면 ArithmeticException 이 났을 것이다.
        BigDecimal interest = InstallmentSavingsInterest.calculate(
                List.of(installment(1, "1000000", LocalDate.of(2026, 1, 1))),
                new BigDecimal("0.0300"), LocalDate.of(2026, 1, 4));

        // 1,000,000 × 0.03 × 3 / 365 = 246.575... → 247
        assertThat(interest).isEqualByComparingTo("247");
    }
}
