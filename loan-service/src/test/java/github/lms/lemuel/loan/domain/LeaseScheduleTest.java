package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스·할부 스케줄 산정 규격.
 *
 * <p>대출 상환표({@link RepaymentSchedule})와 갈리는 지점은 <b>잔존가치</b>다. 리스는 물건 값 전부를
 * 리스료로 회수하지 않고 만기 시점 가치를 남겨 두므로, 만기 잔액이 0 이 아니라 <b>잔존가치로 수렴</b>한다.
 * 잔존가치가 0 이면 할부가 되고, 그때 계산은 원리금균등 대출과 정확히 같아야 한다 — 그 등가를 못 박는다.
 */
class LeaseScheduleTest {

    private static final BigDecimal COST = new BigDecimal("30000000");   // 취득원가 3천만원
    private static final BigDecimal RATE = new BigDecimal("6.0");        // 연 6%

    @Nested
    @DisplayName("금융리스 — 잔존가치를 남긴다")
    class FinanceLease {

        @Test
        @DisplayName("만기 잔액이 잔존가치로 정확히 수렴한다")
        void maturityBalanceEqualsResidualValue() {
            LeaseSchedule schedule = LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, COST, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("6000000"), 36, RATE);

            assertThat(schedule.installments()).hasSize(36);
            assertThat(schedule.installments().getLast().remainingBalance())
                    .isEqualByComparingTo("6000000");
            assertThat(schedule.residualValue()).isEqualByComparingTo("6000000");
        }

        @Test
        @DisplayName("잔존가치가 클수록 월 리스료가 싸다 — 회수할 금액이 줄기 때문")
        void higherResidualLowersRental() {
            BigDecimal low = LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE, COST,
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("3000000"), 36, RATE).monthlyRental();
            BigDecimal high = LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE, COST,
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("9000000"), 36, RATE).monthlyRental();

            assertThat(high).isLessThan(low);
        }

        @Test
        @DisplayName("선수금·보증금은 리스 원금에서 빠진다 — 회수 대상이 아니다")
        void downPaymentAndDepositReduceFinancedAmount() {
            LeaseSchedule schedule = LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, COST,
                    new BigDecimal("3000000"), new BigDecimal("2000000"),
                    new BigDecimal("5000000"), 36, RATE);

            assertThat(schedule.financedAmount()).isEqualByComparingTo("25000000");
            assertThat(schedule.installments().getFirst().remainingBalance()).isLessThan(schedule.financedAmount());
        }

        @Test
        @DisplayName("이자 합계 = 총 리스료 − (리스원금 − 잔존가치): 회수 원금과 이자가 어긋나지 않는다")
        void interestReconcilesWithPrincipalRecovery() {
            LeaseSchedule schedule = LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, COST, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("6000000"), 36, RATE);

            BigDecimal recoveredPrincipal = schedule.financedAmount().subtract(schedule.residualValue());
            assertThat(schedule.totalRental()).isEqualByComparingTo(
                    recoveredPrincipal.add(schedule.totalInterest()));
        }
    }

    @Nested
    @DisplayName("할부 — 잔존가치 없는 특수 케이스")
    class Installment {

        @Test
        @DisplayName("잔존가치 0 인 할부는 원리금균등 대출과 회차별 금액이 완전히 같다")
        void installmentEqualsEqualPaymentLoan() {
            BigDecimal financed = new BigDecimal("24000000");   // 3천만 − 선수금 600만

            LeaseSchedule lease = LeaseSchedule.of(
                    AssetFinanceType.INSTALLMENT, COST, new BigDecimal("6000000"), BigDecimal.ZERO,
                    BigDecimal.ZERO, 36, RATE);
            RepaymentSchedule loan = RepaymentSchedule.of(
                    financed, 36, RATE, RepaymentMethod.EQUAL_PAYMENT);

            assertThat(lease.financedAmount()).isEqualByComparingTo(financed);
            assertThat(lease.monthlyRental()).isEqualByComparingTo(loan.installments().getFirst().payment());
            assertThat(lease.totalInterest()).isEqualByComparingTo(loan.totalInterest());
            assertThat(lease.installments().getLast().remainingBalance()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("할부에 잔존가치를 주면 거부한다 — 할부는 물건 값을 전액 회수하는 상품이다")
        void rejectsResidualOnInstallment() {
            assertThatThrownBy(() -> LeaseSchedule.of(
                    AssetFinanceType.INSTALLMENT, COST, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("1000000"), 36, RATE))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("할부");
        }
    }

    @Nested
    @DisplayName("운용리스 — 반환 전제")
    class OperatingLease {

        @Test
        @DisplayName("잔존가치가 없으면 거부한다 — 반환 전제 상품에 잔존가치 0 은 모순")
        void requiresResidualValue() {
            assertThatThrownBy(() -> LeaseSchedule.of(
                    AssetFinanceType.OPERATING_LEASE, COST, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 36, RATE))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("운용리스");
        }
    }

    @Nested
    @DisplayName("경계·불변식")
    class Invariants {

        @Test
        @DisplayName("무이자(0%) 리스는 회수 원금을 기간으로 나눈 값이 월 리스료")
        void zeroRateSplitsEvenly() {
            LeaseSchedule schedule = LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, new BigDecimal("12000000"),
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2400000"), 12, BigDecimal.ZERO);

            assertThat(schedule.monthlyRental()).isEqualByComparingTo("800000");
            assertThat(schedule.totalInterest()).isEqualByComparingTo("0");
            assertThat(schedule.installments().getLast().remainingBalance()).isEqualByComparingTo("2400000");
        }

        @Test
        @DisplayName("잔존가치가 리스 원금 이상이면 거부한다 — 회수할 원금이 없어 리스료가 성립하지 않는다")
        void rejectsResidualNotBelowFinancedAmount() {
            assertThatThrownBy(() -> LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, COST, BigDecimal.ZERO, BigDecimal.ZERO, COST, 36, RATE))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("잔존가치");
        }

        @Test
        @DisplayName("선수금+보증금이 취득원가 이상이면 거부한다 — 리스할 원금이 남지 않는다")
        void rejectsUpfrontCoveringWholeCost() {
            assertThatThrownBy(() -> LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, COST,
                    new BigDecimal("20000000"), new BigDecimal("10000000"),
                    BigDecimal.ZERO, 36, RATE))
                    .isInstanceOf(LoanInvariantViolationException.class)
                    .hasMessageContaining("리스 원금");
        }

        @Test
        @DisplayName("원 단위로 표현되지 않는 금액은 조용히 반올림하지 않고 거부한다")
        void rejectsSubUnitAmounts() {
            assertThatThrownBy(() -> LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, new BigDecimal("30000000.5"),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 36, RATE))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test
        @DisplayName("기간·이율 경계: 기간 0 이하와 음수 이율은 거부한다")
        void rejectsBadTermOrRate() {
            assertThatThrownBy(() -> LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE, COST,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, RATE))
                    .isInstanceOf(LoanInvariantViolationException.class);
            assertThatThrownBy(() -> LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE, COST,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 36, new BigDecimal("-0.1")))
                    .isInstanceOf(LoanInvariantViolationException.class);
        }

        @Test
        @DisplayName("회차 잔액은 단조 감소하고 회차 원금 합계는 회수 원금과 정확히 일치한다")
        void balanceDecreasesAndPrincipalReconciles() {
            LeaseSchedule schedule = LeaseSchedule.of(
                    AssetFinanceType.FINANCE_LEASE, COST, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("6000000"), 36, RATE);

            BigDecimal previous = schedule.financedAmount();
            BigDecimal principalSum = BigDecimal.ZERO;
            for (LeaseInstallment installment : schedule.installments()) {
                assertThat(installment.remainingBalance()).isLessThan(previous);
                assertThat(installment.rental())
                        .isEqualByComparingTo(installment.principalPortion().add(installment.interest()));
                previous = installment.remainingBalance();
                principalSum = principalSum.add(installment.principalPortion());
            }
            assertThat(principalSum)
                    .isEqualByComparingTo(schedule.financedAmount().subtract(schedule.residualValue()));
        }
    }
}
