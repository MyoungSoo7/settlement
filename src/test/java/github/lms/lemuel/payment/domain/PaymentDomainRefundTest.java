package github.lms.lemuel.payment.domain;

import github.lms.lemuel.common.exception.RefundExceedsPaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class PaymentDomainRefundTest {

    private PaymentDomain captured(BigDecimal amount, BigDecimal alreadyRefunded) {
        return new PaymentDomain(
                1L, 100L, amount, alreadyRefunded,
                PaymentStatus.CAPTURED, "CARD", "PG-X",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("부분환불 정상: 누적 환불액 증가, 상태는 CAPTURED 유지")
    void partial_refund_keeps_captured() {
        PaymentDomain p = captured(new BigDecimal("100000"), BigDecimal.ZERO);

        p.requestRefund(new BigDecimal("30000"));

        assertThat(p.getRefundedAmount()).isEqualByComparingTo("30000");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("70000");
    }

    @Test
    @DisplayName("환불 누적이 결제액과 같아지면 REFUNDED 상태로 전이")
    void cumulative_refund_equal_to_amount_transitions_to_refunded() {
        PaymentDomain p = captured(new BigDecimal("100000"), new BigDecimal("70000"));

        p.requestRefund(new BigDecimal("30000"));

        assertThat(p.getRefundedAmount()).isEqualByComparingTo("100000");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.isFullyRefunded()).isTrue();
    }

    @Test
    @DisplayName("초과 환불은 RefundExceedsPaymentException")
    void over_refund_rejected() {
        PaymentDomain p = captured(new BigDecimal("100000"), new BigDecimal("70000"));

        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("30001")))
                .isInstanceOf(RefundExceedsPaymentException.class)
                .hasMessageContaining("100000");
    }

    @Test
    @DisplayName("0 또는 음수 환불 금액 거부")
    void non_positive_refund_rejected() {
        PaymentDomain p = captured(new BigDecimal("100000"), BigDecimal.ZERO);

        assertThatThrownBy(() -> p.requestRefund(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CAPTURED가 아닌 결제는 환불 불가")
    void cannot_refund_non_captured() {
        PaymentDomain p = new PaymentDomain(
                1L, 100L, new BigDecimal("100000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "PG-X",
                null, LocalDateTime.now(), LocalDateTime.now()
        );

        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 REFUNDED 상태에서는 환불 불가")
    void cannot_refund_already_refunded() {
        PaymentDomain p = new PaymentDomain(
                1L, 100L, new BigDecimal("100000"), new BigDecimal("100000"),
                PaymentStatus.REFUNDED, "CARD", "PG-X",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now()
        );

        assertThatThrownBy(() -> p.requestRefund(new BigDecimal("1")))
                .isInstanceOf(IllegalStateException.class);
    }
}
