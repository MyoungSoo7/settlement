package github.lms.lemuel.account.banking.savings.domain;

import github.lms.lemuel.account.banking.savings.domain.exception.DuplicateInstallmentRoundException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidInstallmentAmountException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidInstallmentRoundException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidSavingsTermsException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAccessDeniedException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAlreadyClosedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적금 애그리거트 단위 테스트.
 *
 * <p>이율 0.0365 는 {@code 0.0365/365 = 0.0001} 이라 "이자 = 납입액 × 0.0001 × 예치일수",
 * 중도해지이율 0.0073 은 일당 0.00002 가 되어 기대값을 손으로 계산할 수 있다.
 */
class InstallmentSavingsTest {

    private static final String DEPOSITOR = "42";
    private static final LocalDate OPENED_ON = LocalDate.of(2026, 1, 1);
    private static final LocalDate MATURITY = LocalDate.of(2026, 4, 1);   // 3개월
    private static final BigDecimal MONTHLY = new BigDecimal("100000");
    private static final BigDecimal ANNUAL_RATE = new BigDecimal("0.0365");
    private static final BigDecimal EARLY_RATE = new BigDecimal("0.0073");

    private static InstallmentSavings fixedSavings() {
        return InstallmentSavings.open(DEPOSITOR, "정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON);
    }

    private static InstallmentSavings flexibleSavings(BigDecimal paymentLimit) {
        return InstallmentSavings.open(DEPOSITOR, "자유적립 6개월", SavingsType.FLEXIBLE,
                null, paymentLimit, ANNUAL_RATE, EARLY_RATE, 6, OPENED_ON);
    }

    @Nested
    class 개설 {

        @Test
        void 개설하면_만기일은_개설일_더하기_계약개월수다() {
            InstallmentSavings savings = fixedSavings();

            assertThat(savings.getMaturityDate()).isEqualTo(MATURITY);
            assertThat(savings.getStatus()).isEqualTo(SavingsStatus.ACTIVE);
            assertThat(savings.getId()).isNull();
            assertThat(savings.getClosedOn()).isNull();
            assertThat(savings.getSettledInterest()).isNull();
            assertThat(savings.getPayoutAmount()).isNull();
            assertThat(savings.getInstallments()).isEmpty();
            assertThat(savings.totalPaidAmount()).isEqualByComparingTo("0");
        }

        @Test
        void 정액적립식은_월_약정액이_필수다() {
            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    null, null, ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);

            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    BigDecimal.ZERO, null, ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }

        @Test
        void 정액적립식에는_회차_한도를_둘_수_없다() {
            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    MONTHLY, new BigDecimal("500000"), ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class)
                    .hasMessageContaining("paymentLimit");
        }

        @Test
        void 자유적립식에는_월_약정액을_둘_수_없다() {
            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "자유", SavingsType.FLEXIBLE,
                    MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 6, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class)
                    .hasMessageContaining("monthlyAmount");
        }

        @Test
        void 자유적립식_회차_한도는_양수여야_한다() {
            assertThatThrownBy(() -> flexibleSavings(BigDecimal.ZERO))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }

        @Test
        void 자유적립식은_한도가_없어도_개설된다() {
            InstallmentSavings savings = flexibleSavings(null);

            assertThat(savings.getPaymentLimit()).isNull();
            assertThat(savings.getMonthlyAmount()).isNull();
            assertThat(savings.getSavingsType()).isEqualTo(SavingsType.FLEXIBLE);
        }

        @Test
        void 계약기간은_1개월_이상이어야_한다() {
            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 0, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }

        @Test
        void 이율은_0_이상_1_미만이어야_한다() {
            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    MONTHLY, null, BigDecimal.ONE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);

            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    MONTHLY, null, ANNUAL_RATE, new BigDecimal("-0.01"), 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);

            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    MONTHLY, null, null, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }

        @Test
        void 예금주와_상품명과_적립방식과_개설일은_필수다() {
            assertThatThrownBy(() -> InstallmentSavings.open(" ", "정액", SavingsType.FIXED,
                    MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);

            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, null, SavingsType.FIXED,
                    MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);

            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", null,
                    MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON))
                    .isInstanceOf(InvalidSavingsTermsException.class);

            assertThatThrownBy(() -> InstallmentSavings.open(DEPOSITOR, "정액", SavingsType.FIXED,
                    MONTHLY, null, ANNUAL_RATE, EARLY_RATE, 3, null))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }
    }

    @Nested
    class 회차납입 {

        @Test
        void 정액적립식은_월_약정액과_정확히_같은_금액만_받는다() {
            InstallmentSavings savings = fixedSavings();

            assertThatThrownBy(() -> savings.pay(1, new BigDecimal("99999"), OPENED_ON))
                    .isInstanceOf(InvalidInstallmentAmountException.class)
                    .hasMessageContaining("정액적립식");
            assertThatThrownBy(() -> savings.pay(1, new BigDecimal("100001"), OPENED_ON))
                    .isInstanceOf(InvalidInstallmentAmountException.class);
        }

        @Test
        void 정액적립식_금액_비교는_소수자리수를_따지지_않는다() {
            InstallmentSavings savings = fixedSavings();

            savings.pay(1, new BigDecimal("100000.00"), OPENED_ON);

            assertThat(savings.getInstallments()).hasSize(1);
        }

        @Test
        void 자유적립식은_회차금액이_자유롭다() {
            InstallmentSavings savings = flexibleSavings(null);

            savings.pay(1, new BigDecimal("10000"), OPENED_ON);
            savings.pay(2, new BigDecimal("999999999"), LocalDate.of(2026, 2, 1));

            assertThat(savings.totalPaidAmount()).isEqualByComparingTo("1000009999");
        }

        @Test
        void 자유적립식은_회차_한도를_넘길_수_없다() {
            InstallmentSavings savings = flexibleSavings(new BigDecimal("500000"));

            savings.pay(1, new BigDecimal("500000"), OPENED_ON);   // 한도 경계는 허용
            assertThatThrownBy(() -> savings.pay(2, new BigDecimal("500001"), LocalDate.of(2026, 2, 1)))
                    .isInstanceOf(InvalidInstallmentAmountException.class)
                    .hasMessageContaining("한도");
        }

        @Test
        void 납입액은_양수여야_한다() {
            InstallmentSavings savings = flexibleSavings(null);

            assertThatThrownBy(() -> savings.pay(1, BigDecimal.ZERO, OPENED_ON))
                    .isInstanceOf(InvalidInstallmentAmountException.class);
            assertThatThrownBy(() -> savings.pay(1, null, OPENED_ON))
                    .isInstanceOf(InvalidInstallmentAmountException.class);
            assertThatThrownBy(() -> savings.pay(1, new BigDecimal("-1"), OPENED_ON))
                    .isInstanceOf(InvalidInstallmentAmountException.class);
        }

        @Test
        void 회차는_1부터_계약개월수_사이여야_한다() {
            InstallmentSavings savings = fixedSavings();

            assertThatThrownBy(() -> savings.pay(0, MONTHLY, OPENED_ON))
                    .isInstanceOf(InvalidInstallmentRoundException.class);
            assertThatThrownBy(() -> savings.pay(4, MONTHLY, OPENED_ON))
                    .isInstanceOf(InvalidInstallmentRoundException.class)
                    .hasMessageContaining("1...3");
        }

        @Test
        void 같은_회차를_두_번_납입할_수_없다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(1, MONTHLY, OPENED_ON);

            assertThatThrownBy(() -> savings.pay(1, MONTHLY, OPENED_ON))
                    .isInstanceOf(DuplicateInstallmentRoundException.class)
                    .hasMessageContaining("1");
            assertThat(savings.getInstallments()).hasSize(1);
        }

        @Test
        void 납입일은_필수이며_개설일_이전일_수_없다() {
            InstallmentSavings savings = fixedSavings();

            assertThatThrownBy(() -> savings.pay(1, MONTHLY, null))
                    .isInstanceOf(InvalidSavingsTermsException.class);
            assertThatThrownBy(() -> savings.pay(1, MONTHLY, OPENED_ON.minusDays(1)))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }

        @Test
        void 회차_기일은_개설일_기준_개월수로_정해진다() {
            InstallmentSavings savings = fixedSavings();

            SavingsInstallment first = savings.pay(1, MONTHLY, OPENED_ON);
            SavingsInstallment third = savings.pay(3, MONTHLY, LocalDate.of(2026, 3, 1));

            assertThat(first.getDueDate()).isEqualTo(OPENED_ON);            // 1회차 기일 = 개설일
            assertThat(third.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        void 기일을_넘겨_내면_연체일수가_기록된다() {
            InstallmentSavings savings = fixedSavings();

            SavingsInstallment onTime = savings.pay(1, MONTHLY, OPENED_ON);
            SavingsInstallment late = savings.pay(2, MONTHLY, LocalDate.of(2026, 2, 11));

            assertThat(onTime.getOverdueDays()).isZero();
            assertThat(onTime.isOverdue()).isFalse();
            assertThat(late.getOverdueDays()).isEqualTo(10);
            assertThat(late.isOverdue()).isTrue();
            assertThat(savings.hasOverdueInstallment()).isTrue();
        }

        @Test
        void 기일보다_일찍_내면_연체일수는_0이다() {
            InstallmentSavings savings = fixedSavings();

            SavingsInstallment early = savings.pay(3, MONTHLY, OPENED_ON);

            assertThat(early.getOverdueDays()).isZero();
            assertThat(savings.hasOverdueInstallment()).isFalse();
        }

        @Test
        void 회차_목록은_회차_오름차순_수정불가_사본이다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(3, MONTHLY, LocalDate.of(2026, 3, 1));
            savings.pay(1, MONTHLY, OPENED_ON);

            List<SavingsInstallment> installments = savings.getInstallments();

            assertThat(installments).extracting(SavingsInstallment::getRound).containsExactly(1, 3);
            assertThatThrownBy(() -> installments.add(installments.get(0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void 납입된_회차인지_알_수_있다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(2, MONTHLY, LocalDate.of(2026, 2, 1));

            assertThat(savings.hasRound(2)).isTrue();
            assertThat(savings.hasRound(1)).isFalse();
        }
    }

    @Nested
    class 해지 {

        @Test
        void 만기해지는_약정이율로_회차별_예치일수만큼_이자를_준다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(1, MONTHLY, OPENED_ON);                          // 90일 → 900
            savings.pay(2, MONTHLY, LocalDate.of(2026, 2, 1));           // 59일 → 590
            savings.pay(3, MONTHLY, LocalDate.of(2026, 3, 1));           // 31일 → 310

            savings.closeOnMaturity(MATURITY);

            assertThat(savings.getStatus()).isEqualTo(SavingsStatus.CLOSED);
            assertThat(savings.getClosedOn()).isEqualTo(MATURITY);
            assertThat(savings.getSettledInterest()).isEqualByComparingTo("1800");
            assertThat(savings.getPayoutAmount()).isEqualByComparingTo("301800");   // 300,000 + 1,800
        }

        @Test
        void 연체는_만기일을_밀지_않고_그_회차_이자만_줄인다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(1, MONTHLY, OPENED_ON);
            savings.pay(2, MONTHLY, LocalDate.of(2026, 2, 1));
            savings.pay(3, MONTHLY, LocalDate.of(2026, 3, 11));          // 10일 연체 → 21일만 예치

            savings.closeOnMaturity(MATURITY);

            assertThat(savings.getMaturityDate()).isEqualTo(MATURITY);   // 만기 불변
            // 정상 납입 대비 100,000 × 0.0001 × 10일 = 100 원만 줄어든다
            assertThat(savings.getSettledInterest()).isEqualByComparingTo("1700");
            assertThat(savings.getPayoutAmount()).isEqualByComparingTo("301700");
        }

        @Test
        void 만기_이후에_해지해도_이자는_만기일까지만_붙는다() {
            InstallmentSavings onMaturity = fixedSavings();
            onMaturity.pay(1, MONTHLY, OPENED_ON);
            onMaturity.closeOnMaturity(MATURITY);

            InstallmentSavings late = fixedSavings();
            late.pay(1, MONTHLY, OPENED_ON);
            late.closeOnMaturity(MATURITY.plusMonths(2));

            assertThat(late.getSettledInterest()).isEqualByComparingTo(onMaturity.getSettledInterest());
            assertThat(late.getClosedOn()).isEqualTo(MATURITY.plusMonths(2));
        }

        @Test
        void 만기해지는_만기일_이전에_할_수_없다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(1, MONTHLY, OPENED_ON);

            assertThatThrownBy(() -> savings.closeOnMaturity(MATURITY.minusDays(1)))
                    .isInstanceOf(InvalidSavingsTermsException.class)
                    .hasMessageContaining("만기일 이후");
        }

        @Test
        void 중도해지는_중도해지이율을_모든_회차에_적용한다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(1, MONTHLY, OPENED_ON);                          // 59일 → 118
            savings.pay(2, MONTHLY, LocalDate.of(2026, 2, 1));           // 28일 → 56

            savings.closeEarly(LocalDate.of(2026, 3, 1));

            assertThat(savings.getStatus()).isEqualTo(SavingsStatus.CLOSED);
            assertThat(savings.getSettledInterest()).isEqualByComparingTo("174");
            assertThat(savings.getPayoutAmount()).isEqualByComparingTo("200174");
        }

        @Test
        void 중도해지_이자는_만기해지_이자보다_적다() {
            InstallmentSavings early = fixedSavings();
            early.pay(1, MONTHLY, OPENED_ON);
            early.closeEarly(LocalDate.of(2026, 3, 31));

            InstallmentSavings matured = fixedSavings();
            matured.pay(1, MONTHLY, OPENED_ON);
            matured.closeOnMaturity(MATURITY);

            assertThat(early.getSettledInterest()).isLessThan(matured.getSettledInterest());
        }

        @Test
        void 중도해지는_만기일_이후에_할_수_없다() {
            InstallmentSavings savings = fixedSavings();

            assertThatThrownBy(() -> savings.closeEarly(MATURITY))
                    .isInstanceOf(InvalidSavingsTermsException.class)
                    .hasMessageContaining("만기일 이전");
        }

        @Test
        void 해지일은_필수이며_개설일_이전일_수_없다() {
            InstallmentSavings savings = fixedSavings();

            assertThatThrownBy(() -> savings.closeOnMaturity(null))
                    .isInstanceOf(InvalidSavingsTermsException.class);
            assertThatThrownBy(() -> savings.closeEarly(OPENED_ON.minusDays(1)))
                    .isInstanceOf(InvalidSavingsTermsException.class);
        }

        @Test
        void 한_번_해지한_적금은_다시_해지할_수_없다() {
            InstallmentSavings savings = fixedSavings();
            savings.pay(1, MONTHLY, OPENED_ON);
            savings.closeOnMaturity(MATURITY);

            assertThatThrownBy(() -> savings.closeOnMaturity(MATURITY))
                    .isInstanceOf(SavingsAlreadyClosedException.class);
            assertThatThrownBy(() -> savings.closeEarly(LocalDate.of(2026, 3, 1)))
                    .isInstanceOf(SavingsAlreadyClosedException.class);
        }

        @Test
        void 해지된_적금에는_회차를_납입할_수_없다() {
            InstallmentSavings savings = fixedSavings();
            savings.closeEarly(LocalDate.of(2026, 2, 1));

            assertThatThrownBy(() -> savings.pay(1, MONTHLY, LocalDate.of(2026, 2, 2)))
                    .isInstanceOf(SavingsAlreadyClosedException.class);
        }

        @Test
        void 한_회차도_없이_해지하면_원금도_이자도_0이다() {
            InstallmentSavings savings = fixedSavings();

            savings.closeEarly(LocalDate.of(2026, 2, 1));

            assertThat(savings.getSettledInterest()).isEqualByComparingTo("0");
            assertThat(savings.getPayoutAmount()).isEqualByComparingTo("0");
        }
    }

    @Nested
    class 소유권과_복원 {

        @Test
        void 본인이_아니면_접근이_거절된다() {
            InstallmentSavings savings = fixedSavings();

            savings.assertOwnedBy(DEPOSITOR);   // 예외 없음
            assertThatThrownBy(() -> savings.assertOwnedBy("999"))
                    .isInstanceOf(SavingsAccessDeniedException.class);
            assertThatThrownBy(() -> savings.assertOwnedBy(null))
                    .isInstanceOf(SavingsAccessDeniedException.class);
        }

        @Test
        void 복원은_저장된_상태를_그대로_되살린다() {
            SavingsInstallment installment = SavingsInstallment.reconstitute(
                    7L, 1, new BigDecimal("100000"), OPENED_ON, OPENED_ON.plusDays(3), 3);

            InstallmentSavings savings = InstallmentSavings.reconstitute(
                    5L, DEPOSITOR, "정액적립 3개월", SavingsType.FIXED, MONTHLY, null,
                    ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON, MATURITY,
                    SavingsStatus.CLOSED, MATURITY, new BigDecimal("900"), new BigDecimal("100900"),
                    List.of(installment));

            assertThat(savings.getId()).isEqualTo(5L);
            assertThat(savings.getProductName()).isEqualTo("정액적립 3개월");
            assertThat(savings.getEarlyTerminationRate()).isEqualByComparingTo(EARLY_RATE);
            assertThat(savings.getTermMonths()).isEqualTo(3);
            assertThat(savings.getStatus()).isEqualTo(SavingsStatus.CLOSED);
            assertThat(savings.getPayoutAmount()).isEqualByComparingTo("100900");
            assertThat(savings.getInstallments()).hasSize(1);
            assertThat(savings.getInstallments().get(0).getId()).isEqualTo(7L);
            assertThat(savings.getInstallments().get(0).getOverdueDays()).isEqualTo(3);
        }

        @Test
        void 회차가_null_이면_빈_목록으로_복원된다() {
            InstallmentSavings savings = InstallmentSavings.reconstitute(
                    5L, DEPOSITOR, "정액", SavingsType.FIXED, MONTHLY, null,
                    ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON, MATURITY,
                    SavingsStatus.ACTIVE, null, null, null, null);

            assertThat(savings.getInstallments()).isEmpty();
        }

        @Test
        void 생성자에_넘긴_목록을_나중에_바꿔도_애그리거트는_영향받지_않는다() {
            List<SavingsInstallment> mutable = new java.util.ArrayList<>();
            mutable.add(SavingsInstallment.reconstitute(1L, 1, MONTHLY, OPENED_ON, OPENED_ON, 0));

            InstallmentSavings savings = InstallmentSavings.reconstitute(
                    5L, DEPOSITOR, "정액", SavingsType.FIXED, MONTHLY, null,
                    ANNUAL_RATE, EARLY_RATE, 3, OPENED_ON, MATURITY,
                    SavingsStatus.ACTIVE, null, null, null, mutable);

            mutable.add(SavingsInstallment.reconstitute(2L, 2, MONTHLY, OPENED_ON, OPENED_ON, 0));

            assertThat(savings.getInstallments()).hasSize(1);
        }
    }
}
