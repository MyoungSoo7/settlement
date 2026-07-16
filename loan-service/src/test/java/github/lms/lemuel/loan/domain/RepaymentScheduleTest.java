package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상환 스케줄 계산 회귀 가드. 대표값 손계산 + 방식 불변식(원금 정합·잔액 소멸·합계 일관성) +
 * 라운딩 경계 + 무이자 + 입력 검증을 전수 확인한다.
 */
class RepaymentScheduleTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // ─── 만기일시상환 (BULLET) 손계산 ──────────────────────────────────────────────
    // 원금 1,200,000 · 12개월 · 연 6%(월 0.5%) → 매월 이자 6,000, 원금은 12회차 일시.

    @Nested
    class 만기일시상환 {
        private final RepaymentSchedule s =
                RepaymentSchedule.of(bd("1200000"), 12, bd("6.0"), RepaymentMethod.BULLET);

        @Test void 회차수는_기간과_같다() {
            assertThat(s.installments()).hasSize(12);
        }

        @Test void 중간회차는_이자만_납입하고_원금잔액은_유지된다() {
            RepaymentInstallment first = s.installments().get(0);
            assertThat(first.installmentNo()).isEqualTo(1);
            assertThat(first.principalPortion()).isEqualByComparingTo("0");
            assertThat(first.interest()).isEqualByComparingTo("6000");
            assertThat(first.payment()).isEqualByComparingTo("6000");
            assertThat(first.remainingBalance()).isEqualByComparingTo("1200000");
        }

        @Test void 만기회차는_원금전액과_이자를_함께_납입한다() {
            RepaymentInstallment last = s.installments().get(11);
            assertThat(last.installmentNo()).isEqualTo(12);
            assertThat(last.principalPortion()).isEqualByComparingTo("1200000");
            assertThat(last.interest()).isEqualByComparingTo("6000");
            assertThat(last.payment()).isEqualByComparingTo("1206000");
            assertThat(last.remainingBalance()).isEqualByComparingTo("0");
        }

        @Test void 합계는_이자72000_납입1272000() {
            assertThat(s.totalPrincipal()).isEqualByComparingTo("1200000");
            assertThat(s.totalInterest()).isEqualByComparingTo("72000");
            assertThat(s.totalPayment()).isEqualByComparingTo("1272000");
        }
    }

    // ─── 원금균등상환 (EQUAL_PRINCIPAL) 손계산 ─────────────────────────────────────
    // 매월 원금 100,000 고정, 이자는 잔액 기준 매월 500 씩 감소. 총이자 39,000.

    @Nested
    class 원금균등상환 {
        private final RepaymentSchedule s =
                RepaymentSchedule.of(bd("1200000"), 12, bd("6.0"), RepaymentMethod.EQUAL_PRINCIPAL);

        @Test void 첫회차_원금100000_이자6000() {
            RepaymentInstallment first = s.installments().get(0);
            assertThat(first.principalPortion()).isEqualByComparingTo("100000");
            assertThat(first.interest()).isEqualByComparingTo("6000");
            assertThat(first.payment()).isEqualByComparingTo("106000");
            assertThat(first.remainingBalance()).isEqualByComparingTo("1100000");
        }

        @Test void 둘째회차_이자는_5500으로_감소() {
            assertThat(s.installments().get(1).interest()).isEqualByComparingTo("5500");
        }

        @Test void 마지막회차_잔액소멸_이자500() {
            RepaymentInstallment last = s.installments().get(11);
            assertThat(last.principalPortion()).isEqualByComparingTo("100000");
            assertThat(last.interest()).isEqualByComparingTo("500");
            assertThat(last.remainingBalance()).isEqualByComparingTo("0");
        }

        @Test void 총이자는_39000() {
            assertThat(s.totalInterest()).isEqualByComparingTo("39000");
            assertThat(s.totalPayment()).isEqualByComparingTo("1239000");
        }
    }

    // ─── 원리금균등상환 (EQUAL_PAYMENT) 구조 ───────────────────────────────────────

    @Nested
    class 원리금균등상환 {
        private final RepaymentSchedule s =
                RepaymentSchedule.of(bd("1200000"), 12, bd("6.0"), RepaymentMethod.EQUAL_PAYMENT);

        @Test void 마지막_직전까지_납입액이_모두_동일하다() {
            BigDecimal fixed = s.installments().get(0).payment();
            for (int k = 0; k < 11; k++) {
                assertThat(s.installments().get(k).payment())
                        .as("회차 %d", k + 1)
                        .isEqualByComparingTo(fixed);
            }
        }

        @Test void 초반_이자비중이_후반보다_크다() {
            assertThat(s.installments().get(0).interest())
                    .isGreaterThan(s.installments().get(11).interest());
        }

        @Test void 총이자는_만기일시보다_작고_원금균등보다_크다() {
            BigDecimal bulletInterest = RepaymentSchedule
                    .of(bd("1200000"), 12, bd("6.0"), RepaymentMethod.BULLET).totalInterest();
            BigDecimal equalPrincipalInterest = RepaymentSchedule
                    .of(bd("1200000"), 12, bd("6.0"), RepaymentMethod.EQUAL_PRINCIPAL).totalInterest();
            assertThat(s.totalInterest())
                    .isLessThan(bulletInterest)
                    .isGreaterThan(equalPrincipalInterest);
        }
    }

    // ─── 방식 공통 불변식 ─────────────────────────────────────────────────────────

    @Nested
    class 공통불변식 {
        @ParameterizedTest
        @EnumSource(RepaymentMethod.class)
        void 원금합계는_신청원금과_정확히_일치하고_잔액은_0으로_소멸한다(RepaymentMethod method) {
            RepaymentSchedule s = RepaymentSchedule.of(bd("1000000"), 7, bd("4.8"), method);

            BigDecimal principalSum = s.installments().stream()
                    .map(RepaymentInstallment::principalPortion)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(principalSum).isEqualByComparingTo("1000000");
            assertThat(s.installments().get(6).remainingBalance()).isEqualByComparingTo("0");
        }

        @ParameterizedTest
        @EnumSource(RepaymentMethod.class)
        void 총납입액은_원금과_이자의_합이다(RepaymentMethod method) {
            RepaymentSchedule s = RepaymentSchedule.of(bd("3333333"), 5, bd("7.3"), method);
            assertThat(s.totalPayment())
                    .isEqualByComparingTo(s.totalPrincipal().add(s.totalInterest()));
        }

        @ParameterizedTest
        @EnumSource(RepaymentMethod.class)
        void 회차별_납입액은_원금과_이자의_합이다(RepaymentMethod method) {
            RepaymentSchedule s = RepaymentSchedule.of(bd("1000000"), 6, bd("6.0"), method);
            for (RepaymentInstallment it : s.installments()) {
                assertThat(it.payment())
                        .as("회차 %d", it.installmentNo())
                        .isEqualByComparingTo(it.principalPortion().add(it.interest()));
            }
        }
    }

    // ─── 라운딩: 원금이 기간으로 나누어떨어지지 않을 때 마지막 회차가 잔여를 흡수 ─────────────

    @Test
    void 라운딩_잔여는_마지막회차가_흡수한다() {
        RepaymentSchedule s =
                RepaymentSchedule.of(bd("1000000"), 3, bd("0.0"), RepaymentMethod.EQUAL_PRINCIPAL);
        List<RepaymentInstallment> rows = s.installments();
        assertThat(rows.get(0).principalPortion()).isEqualByComparingTo("333333");
        assertThat(rows.get(1).principalPortion()).isEqualByComparingTo("333333");
        assertThat(rows.get(2).principalPortion()).isEqualByComparingTo("333334"); // 잔여 흡수
    }

    // ─── 무이자(연 0%) ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(RepaymentMethod.class)
    void 무이자면_이자합계0_납입합계는_원금(RepaymentMethod method) {
        RepaymentSchedule s = RepaymentSchedule.of(bd("1200000"), 12, bd("0.0"), method);
        assertThat(s.totalInterest()).isEqualByComparingTo("0");
        assertThat(s.totalPayment()).isEqualByComparingTo("1200000");
    }

    // ─── 입력 검증 ────────────────────────────────────────────────────────────────

    @Nested
    class 입력검증 {
        @Test void 원금이_0이하면_예외() {
            assertThatThrownBy(() -> RepaymentSchedule.of(bd("0"), 12, bd("6.0"), RepaymentMethod.BULLET))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test void 기간이_1미만이면_예외() {
            assertThatThrownBy(() -> RepaymentSchedule.of(bd("1000000"), 0, bd("6.0"), RepaymentMethod.BULLET))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test void 연이율이_음수면_예외() {
            assertThatThrownBy(() -> RepaymentSchedule.of(bd("1000000"), 12, bd("-0.1"), RepaymentMethod.BULLET))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test void 방식이_null이면_예외() {
            assertThatThrownBy(() -> RepaymentSchedule.of(bd("1000000"), 12, bd("6.0"), null))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }
    }

    @Test
    void 단일회차_만기일시도_정상산정된다() {
        RepaymentSchedule s = RepaymentSchedule.of(bd("500000"), 1, bd("12.0"), RepaymentMethod.BULLET);
        assertThat(s.installments()).hasSize(1);
        RepaymentInstallment only = s.installments().get(0);
        assertThat(only.principalPortion()).isEqualByComparingTo("500000");
        assertThat(only.interest()).isEqualByComparingTo("5000"); // 500,000 × 1% = 5,000
        assertThat(only.remainingBalance()).isEqualByComparingTo("0");
    }
}
