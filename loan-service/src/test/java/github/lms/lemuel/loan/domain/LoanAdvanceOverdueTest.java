package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 선정산 대출 연체(OVERDUE)·상각(WRITTEN_OFF) 전이 회귀 가드 — markOverdue()/writeOff() 도메인 메서드와
 * LoanStatus 전이표(DISBURSED→OVERDUE→REPAID|WRITTEN_OFF)를 전수 검증한다.
 */
class LoanAdvanceOverdueTest {

    private LoanAdvance disbursed() {
        LoanAdvance loan = LoanAdvance.request(1L, new BigDecimal("800000"), new BigDecimal("800"));
        loan.approve();
        loan.disburse(); // outstanding = 800800
        return loan;
    }

    @Test
    void 실행된_대출은_연체_처리되어_OVERDUE_이고_잔액을_보존한다() {
        LoanAdvance loan = disbursed();
        loan.markOverdue();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.OVERDUE);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("800800");
    }

    @Test
    void 연체된_대출도_상환되면_REPAID_로_회수된다() {
        LoanAdvance loan = disbursed();
        loan.markOverdue();
        BigDecimal deducted = loan.applyRepayment(new BigDecimal("800800"));
        assertThat(deducted).isEqualByComparingTo("800800");
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    void 연체된_대출의_부분상환은_OVERDUE_를_유지한다() {
        LoanAdvance loan = disbursed();
        loan.markOverdue();
        loan.applyRepayment(new BigDecimal("300000"));
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.OVERDUE);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("500800");
    }

    @Test
    void 연체된_대출은_상각되면_WRITTEN_OFF_이고_손실액을_반환한다() {
        LoanAdvance loan = disbursed();
        loan.markOverdue();

        BigDecimal loss = loan.writeOff();

        assertThat(loss).isEqualByComparingTo("800800"); // 미상환잔액 = 상각 손실
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("800800"); // 손실 근거로 보존
    }

    @Test
    void 실행되지_않은_대출은_연체_처리할_수_없다() {
        LoanAdvance requested = LoanAdvance.request(1L, new BigDecimal("1000"), new BigDecimal("10"));
        assertThatThrownBy(requested::markOverdue)
                .isInstanceOf(LoanInvariantViolationException.class); // outstanding=0

        LoanAdvance approved = LoanAdvance.request(1L, new BigDecimal("1000"), new BigDecimal("10"));
        approved.approve();
        assertThatThrownBy(approved::markOverdue)
                .isInstanceOf(LoanInvariantViolationException.class); // outstanding=0
    }

    @Test
    void 연체가_아닌_대출은_상각할_수_없다() {
        LoanAdvance loan = disbursed(); // DISBURSED — 연체 없이 바로 상각 불가
        assertThatThrownBy(loan::writeOff)
                .isInstanceOfSatisfying(InvalidLoanStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(LoanStatus.DISBURSED);
                    assertThat(ex.getTo()).isEqualTo(LoanStatus.WRITTEN_OFF);
                });
    }

    @Test
    void 상각된_대출은_추가_전이가_불가하다() {
        LoanAdvance loan = disbursed();
        loan.markOverdue();
        loan.writeOff();
        // WRITTEN_OFF 은 종착 상태 — 상환/재연체 모두 상태머신 가드가 차단한다(전이표에 없음).
        // 잔액은 손실 근거로 보존(>0)되므로 invariant 가 아니라 전이 가드가 먼저 막는다.
        assertThatThrownBy(() -> loan.applyRepayment(new BigDecimal("100")))
                .isInstanceOfSatisfying(InvalidLoanStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(LoanStatus.WRITTEN_OFF);
                    assertThat(ex.getTo()).isEqualTo(LoanStatus.REPAID);
                });
        assertThatThrownBy(loan::markOverdue)
                .isInstanceOfSatisfying(InvalidLoanStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(LoanStatus.WRITTEN_OFF);
                    assertThat(ex.getTo()).isEqualTo(LoanStatus.OVERDUE);
                });
    }
}
