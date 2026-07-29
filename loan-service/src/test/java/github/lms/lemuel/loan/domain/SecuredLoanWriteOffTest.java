package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보 실행 후 상각 (Phase 2).
 *
 * <p>상각은 <b>기한이익상실 이후에만</b> 가능하다 — 담보를 실행해 회수 부족이 확정돼야 손실이 성립하고,
 * 그 전에 상각하면 아직 회수 가능한 채권을 손실로 털어 버린다.
 */
class SecuredLoanWriteOffTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 9, 0);

    private static SecuredLoan defaulted() {
        Collateral collateral = Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal("500000000"), NOW);
        collateral.activate();
        SecuredLoan loan = SecuredLoan.requestMortgage(Borrower.individual(42L, "홍길동"), collateral,
                new BigDecimal("300000000"), 360, new BigDecimal("4.30"),
                RepaymentMethod.EQUAL_PAYMENT, NOW);
        loan.approve();
        loan.disburse(NOW);
        loan.markOverdue();
        loan.accelerate();
        return loan;
    }

    @Test
    void 상각하면_잔액이_0이_되고_상각액을_돌려준다() {
        SecuredLoan loan = defaulted();

        BigDecimal writtenOff = loan.writeOff();

        assertThat(writtenOff).isEqualByComparingTo("300000000");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.WRITTEN_OFF);
    }

    @Test
    void 일부_회수후_상각하면_잔여분만_상각된다() {
        SecuredLoan loan = defaulted();
        loan.repay(new BigDecimal("200000000"));   // 담보 처분으로 2억 회수

        BigDecimal writtenOff = loan.writeOff();

        assertThat(writtenOff).isEqualByComparingTo("100000000");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getStatus()).isEqualTo(SecuredLoanStatus.WRITTEN_OFF);
    }

    @Test
    void 전액_회수되면_완제이므로_상각할_수_없다() {
        SecuredLoan loan = defaulted();
        loan.repay(new BigDecimal("300000000"));   // 전액 회수 → REPAID

        assertThatThrownBy(loan::writeOff).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 기한이익상실_전에는_상각할_수_없다() {
        Collateral collateral = Collateral.pledge(CollateralType.REAL_ESTATE, "서울시 강남구",
                new BigDecimal("500000000"), NOW);
        collateral.activate();
        SecuredLoan loan = SecuredLoan.requestMortgage(Borrower.individual(42L, "홍길동"), collateral,
                new BigDecimal("300000000"), 360, new BigDecimal("4.30"),
                RepaymentMethod.EQUAL_PAYMENT, NOW);
        loan.approve();
        loan.disburse(NOW);
        assertThatThrownBy(loan::writeOff).isInstanceOf(InvalidLoanStateException.class);

        loan.markOverdue();
        assertThatThrownBy(loan::writeOff).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 이미_상각된_대출은_다시_상각할_수_없다() {
        SecuredLoan loan = defaulted();
        loan.writeOff();

        assertThatThrownBy(loan::writeOff).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 상각된_대출은_더_상환할_수_없다() {
        SecuredLoan loan = defaulted();
        loan.writeOff();

        assertThatThrownBy(() -> loan.repay(new BigDecimal("1000")))
                .isInstanceOf(InvalidLoanStateException.class);
    }
}
