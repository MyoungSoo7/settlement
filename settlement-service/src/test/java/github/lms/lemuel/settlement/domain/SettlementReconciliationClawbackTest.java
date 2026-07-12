package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Settlement#applyReconciliationClawback} 도메인 규칙 검증.
 */
class SettlementReconciliationClawbackTest {

    /** 결제 100,000 / 3.5% 수수료 → net 96,500 인 REQUESTED 정산. */
    private Settlement newSettlement() {
        return Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
    }

    @Test
    @DisplayName("applyReconciliationClawback: net 를 clawback 만큼 축소하고 refundedAmount 는 건드리지 않는다")
    void reducesNet_withoutTouchingRefundedTotal() {
        Settlement s = newSettlement();
        assertThat(s.getNetAmount()).isEqualByComparingTo("96500.00");

        s.applyReconciliationClawback(new BigDecimal("1000"));

        assertThat(s.getNetAmount()).isEqualByComparingTo("95500.00");
        // refundedAmount running total 은 오염되면 안 된다 (실제 환불과 이중 계상 방지)
        assertThat(s.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
    }

    @Test
    @DisplayName("applyReconciliationClawback: DONE 정산은 불변 → IllegalStateException")
    void doneSettlement_throws() {
        Settlement s = newSettlement();
        s.confirm(); // REQUESTED → PROCESSING → DONE
        assertThat(s.isDone()).isTrue();

        assertThatThrownBy(() -> s.applyReconciliationClawback(new BigDecimal("1000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DONE settlement is immutable");
    }

    @Test
    @DisplayName("applyReconciliationClawback: net 이 0 이하가 되면 CANCELED")
    void netZeroOrNegative_cancels() {
        Settlement s = newSettlement(); // net 96,500

        s.applyReconciliationClawback(new BigDecimal("96500"));

        assertThat(s.getNetAmount()).isEqualByComparingTo("0.00");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
    }

    @Test
    @DisplayName("applyReconciliationClawback: net 초과 회수도 CANCELED (음수 net)")
    void netNegative_cancels() {
        Settlement s = newSettlement(); // net 96,500

        s.applyReconciliationClawback(new BigDecimal("100000"));

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
    }

    @Test
    @DisplayName("applyReconciliationClawback: 0 또는 음수 금액은 거부")
    void nonPositiveAmount_rejected() {
        Settlement s = newSettlement();
        assertThatThrownBy(() -> s.applyReconciliationClawback(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.applyReconciliationClawback(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.applyReconciliationClawback(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
