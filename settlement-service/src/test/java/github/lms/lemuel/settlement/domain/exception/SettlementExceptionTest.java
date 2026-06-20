package github.lms.lemuel.settlement.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SettlementExceptionTest {

    // ── PaymentNotFoundException ──

    @Test @DisplayName("PaymentNotFoundException: 문자열 생성자")
    void paymentNotFound_stringConstructor() {
        var ex = new PaymentNotFoundException("결제를 찾을 수 없습니다");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("결제를 찾을 수 없습니다");
    }

    @Test @DisplayName("PaymentNotFoundException: Long 생성자")
    void paymentNotFound_longConstructor() {
        var ex = new PaymentNotFoundException(42L);
        assertThat(ex.getMessage()).isEqualTo("Payment not found with id: 42");
    }

    // ── SettlementNotFoundException ──

    @Test @DisplayName("SettlementNotFoundException: 문자열 생성자")
    void settlementNotFound_stringConstructor() {
        var ex = new SettlementNotFoundException("정산을 찾을 수 없습니다");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("정산을 찾을 수 없습니다");
    }

    @Test @DisplayName("SettlementNotFoundException: Long 생성자")
    void settlementNotFound_longConstructor() {
        var ex = new SettlementNotFoundException(99L);
        assertThat(ex.getMessage()).isEqualTo("Settlement not found with id: 99");
    }
}
