package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanAdvanceTest {

    private LoanAdvance requested() {
        // 셀러 1, 선지급 원금 800,000, 수수료 800
        return LoanAdvance.request(1L, new BigDecimal("800000"), new BigDecimal("800"));
    }

    @Test
    void 신청시_REQUESTED_이고_미상환잔액은_0() {
        LoanAdvance loan = requested();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REQUESTED);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    void 승인_실행_후_미상환잔액은_원금더하기수수료() {
        LoanAdvance loan = requested();
        loan.approve();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.APPROVED);

        loan.disburse();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("800800"); // 800000 + 800
    }

    @Test
    void REQUESTED_가_아닌_상태에서_승인하면_예외() {
        LoanAdvance loan = requested();
        loan.approve();
        assertThatThrownBy(loan::approve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void APPROVED_가_아닌_상태에서_실행하면_예외() {
        LoanAdvance loan = requested();
        assertThatThrownBy(loan::disburse).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 부분상환은_미상환잔액을_줄이고_차감액을_반환한다() {
        LoanAdvance loan = requested();
        loan.approve();
        loan.disburse(); // outstanding = 800800

        BigDecimal deducted = loan.applyRepayment(new BigDecimal("300000"));

        assertThat(deducted).isEqualByComparingTo("300000");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("500800");
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED); // 아직 미상환 잔액 있음
    }

    @Test
    void 차감액은_미상환잔액을_초과하지_않는다_초과상환시_잔액만큼만() {
        LoanAdvance loan = requested();
        loan.approve();
        loan.disburse(); // outstanding = 800800

        BigDecimal deducted = loan.applyRepayment(new BigDecimal("1000000")); // 잔액보다 큼

        assertThat(deducted).isEqualByComparingTo("800800"); // 잔액만큼만 차감
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REPAID);
    }

    @Test
    void 전액상환되면_REPAID() {
        LoanAdvance loan = requested();
        loan.approve();
        loan.disburse();
        loan.applyRepayment(new BigDecimal("800800"));
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    void DISBURSED_가_아닌_상태에서_상환하면_예외() {
        LoanAdvance loan = requested(); // REQUESTED
        assertThatThrownBy(() -> loan.applyRepayment(new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 거절은_REQUESTED나_APPROVED_에서만_가능() {
        LoanAdvance loan = requested();
        loan.reject();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REJECTED);
    }
}
