package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보/개인신용 대출 애그리거트 규칙.
 *
 * <p>한 애그리거트가 두 상품을 수용한다 — 주택담보(담보 필수)와 개인신용(담보 없음, CB 점수 필수).
 * 상품별 필수 요소는 <b>생성 시점에 강제</b>되므로, 담보 없는 주택담보대출이나 CB 점수 없는
 * 개인신용대출은 만들어질 수 없다.
 */
class SecuredLoanTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 10, 0);
    private static final Borrower INDIVIDUAL = Borrower.individual(42L, "홍길동");

    private static Collateral activeCollateral() {
        Collateral collateral = Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal("500000000"), NOW);
        collateral.activate();
        return collateral;
    }

    private static SecuredLoan mortgage() {
        return SecuredLoan.requestMortgage(INDIVIDUAL, activeCollateral(),
                new BigDecimal("300000000"), 360, new BigDecimal("4.5"),
                RepaymentMethod.EQUAL_PAYMENT, NOW);
    }

    private static SecuredLoan personalCredit() {
        return SecuredLoan.requestPersonalCredit(INDIVIDUAL,
                new BigDecimal("10000000"), 36, new BigDecimal("8.0"),
                RepaymentMethod.EQUAL_PAYMENT, 780, "B", NOW);
    }

    // ─── 생성: 주택담보 ────────────────────────────────────────────────────────

    @Test
    void 주택담보대출은_담보를_갖고_REQUESTED_로_시작한다() {
        SecuredLoan loan = mortgage();

        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REQUESTED);
        assertThat(loan.getProductType()).isEqualTo(LoanProductType.MORTGAGE);
        assertThat(loan.getCollateral()).isNotNull();
        assertThat(loan.getPrincipal()).isEqualByComparingTo("300000000");
        assertThat(loan.getTermMonths()).isEqualTo(360);
        assertThat(loan.getAnnualRatePercent()).isEqualByComparingTo("4.5");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getBorrower()).isEqualTo(INDIVIDUAL);
        assertThat(loan.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 주택담보대출에_담보가_없으면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(INDIVIDUAL, null,
                new BigDecimal("300000000"), 360, new BigDecimal("4.5"),
                RepaymentMethod.EQUAL_PAYMENT, NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 주택담보대출은_CB점수를_갖지_않는다() {
        assertThat(mortgage().getCreditScore()).isNull();
    }

    // ─── 생성: 개인신용 ────────────────────────────────────────────────────────

    @Test
    void 개인신용대출은_담보없이_CB점수를_갖는다() {
        SecuredLoan loan = personalCredit();

        assertThat(loan.getProductType()).isEqualTo(LoanProductType.PERSONAL_CREDIT);
        assertThat(loan.getCollateral()).isNull();
        assertThat(loan.getCreditScore()).isEqualTo(780);
        assertThat(loan.getCreditGrade()).isEqualTo("B");
    }

    @Test
    void 개인신용대출에_CB점수가_없으면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestPersonalCredit(INDIVIDUAL,
                new BigDecimal("10000000"), 36, new BigDecimal("8.0"),
                RepaymentMethod.EQUAL_PAYMENT, null, "B", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 개인신용대출에_신용등급이_없으면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestPersonalCredit(INDIVIDUAL,
                new BigDecimal("10000000"), 36, new BigDecimal("8.0"),
                RepaymentMethod.EQUAL_PAYMENT, 780, "  ", NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    // ─── 공통 불변식 ──────────────────────────────────────────────────────────

    @Test
    void 차주가_없으면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(null, activeCollateral(),
                new BigDecimal("300000000"), 360, new BigDecimal("4.5"),
                RepaymentMethod.EQUAL_PAYMENT, NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 원금이_0이하면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(INDIVIDUAL, activeCollateral(),
                BigDecimal.ZERO, 360, new BigDecimal("4.5"), RepaymentMethod.EQUAL_PAYMENT, NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 기간이_1개월_미만이면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(INDIVIDUAL, activeCollateral(),
                new BigDecimal("300000000"), 0, new BigDecimal("4.5"),
                RepaymentMethod.EQUAL_PAYMENT, NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 연이율이_음수면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(INDIVIDUAL, activeCollateral(),
                new BigDecimal("300000000"), 360, new BigDecimal("-0.1"),
                RepaymentMethod.EQUAL_PAYMENT, NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 상환방식이_없으면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(INDIVIDUAL, activeCollateral(),
                new BigDecimal("300000000"), 360, new BigDecimal("4.5"), null, NOW))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 신청시각이_없으면_예외() {
        assertThatThrownBy(() -> SecuredLoan.requestMortgage(INDIVIDUAL, activeCollateral(),
                new BigDecimal("300000000"), 360, new BigDecimal("4.5"),
                RepaymentMethod.EQUAL_PAYMENT, null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    // ─── 생명주기 ────────────────────────────────────────────────────────────

    @Test
    void 승인후_실행하면_미상환잔액은_원금이다() {
        SecuredLoan loan = mortgage();
        loan.approve();
        loan.disburse();

        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);
        // 장기 분할상환이라 이자는 회차별로 발생한다 — 실행 시점 잔액은 원금뿐이다.
        assertThat(loan.getOutstanding()).isEqualByComparingTo("300000000");
    }

    @Test
    void 승인없이_실행할_수_없다() {
        assertThatThrownBy(mortgage()::disburse).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 담보가_유효하지_않으면_실행할_수_없다() {
        Collateral pledgedOnly = Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal("500000000"), NOW);
        SecuredLoan loan = SecuredLoan.requestMortgage(INDIVIDUAL, pledgedOnly,
                new BigDecimal("300000000"), 360, new BigDecimal("4.5"),
                RepaymentMethod.EQUAL_PAYMENT, NOW);
        loan.approve();

        assertThatThrownBy(loan::disburse).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 거절된_대출은_실행할_수_없다() {
        SecuredLoan loan = mortgage();
        loan.reject();
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REJECTED);
        assertThatThrownBy(loan::approve).isInstanceOf(InvalidLoanStateException.class);
    }

    // ─── 상환 ────────────────────────────────────────────────────────────────

    private static SecuredLoan disbursed() {
        SecuredLoan loan = mortgage();
        loan.approve();
        loan.disburse();
        return loan;
    }

    @Test
    void 부분상환은_잔액을_차감한다() {
        SecuredLoan loan = disbursed();
        BigDecimal deducted = loan.repay(new BigDecimal("100000000"));

        assertThat(deducted).isEqualByComparingTo("100000000");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("200000000");
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);
    }

    @Test
    void 잔액을_초과해_상환해도_잔액만큼만_차감된다() {
        SecuredLoan loan = disbursed();
        BigDecimal deducted = loan.repay(new BigDecimal("999999999999"));

        assertThat(deducted).isEqualByComparingTo("300000000");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
    }

    @Test
    void 상환액이_0이하면_예외() {
        assertThatThrownBy(() -> disbursed().repay(BigDecimal.ZERO))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 실행전에는_상환할_수_없다() {
        assertThatThrownBy(() -> mortgage().repay(new BigDecimal("1000")))
                .isInstanceOf(InvalidLoanStateException.class);
    }

    // ─── 연체 · 기한이익상실 ───────────────────────────────────────────────────

    @Test
    void 실행된_대출은_연체될_수_있다() {
        SecuredLoan loan = disbursed();
        loan.markOverdue();
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.OVERDUE);
    }

    @Test
    void 연체된_대출은_기한이익상실될_수_있다() {
        SecuredLoan loan = disbursed();
        loan.markOverdue();
        loan.accelerate();
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.DEFAULTED);
    }

    @Test
    void 연체를_거치지_않고_기한이익상실할_수_없다() {
        assertThatThrownBy(disbursed()::accelerate).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 연체된_대출도_상환하면_완제된다() {
        SecuredLoan loan = disbursed();
        loan.markOverdue();
        loan.repay(new BigDecimal("300000000"));
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
    }

    @Test
    void 기한이익상실된_대출도_전액회수되면_완제된다() {
        SecuredLoan loan = disbursed();
        loan.markOverdue();
        loan.accelerate();
        loan.repay(new BigDecimal("300000000"));
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.REPAID);
    }

    // ─── 상환 스케줄 ──────────────────────────────────────────────────────────

    @Test
    void 상환스케줄은_원금과_기간으로_산정된다() {
        RepaymentSchedule schedule = mortgage().repaymentSchedule();

        assertThat(schedule.installments()).hasSize(360);
        assertThat(schedule.totalPrincipal()).isEqualByComparingTo("300000000");
        assertThat(schedule.method()).isEqualTo(RepaymentMethod.EQUAL_PAYMENT);
    }

    // ─── 재구성 ──────────────────────────────────────────────────────────────

    @Test
    void 영속상태를_재구성한다() {
        SecuredLoan loan = SecuredLoan.reconstitute(11L, INDIVIDUAL, LoanProductType.PERSONAL_CREDIT, null,
                new BigDecimal("10000000.00"), 36, new BigDecimal("8.0"), RepaymentMethod.EQUAL_PAYMENT,
                780, "B", new BigDecimal("5000000.00"), SecuredLoanStatus.DISBURSED, NOW);

        assertThat(loan.getId()).isEqualTo(11L);
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.DISBURSED);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("5000000");
    }
}
