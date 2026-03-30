package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class PaymentDomainTest {

    private PaymentDomain createReadyPayment() {
        return new PaymentDomain(1L, new BigDecimal("10000"), "CARD");
    }

    private PaymentDomain createCapturedPayment() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx-123");
        p.capture();
        return p;
    }

    @Test @DisplayName("생성 시 READY 상태, 환불금액 0")
    void creation() {
        PaymentDomain p = createReadyPayment();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.getOrderId()).isEqualTo(1L);
        assertThat(p.getAmount()).isEqualByComparingTo("10000");
    }

    @Test @DisplayName("READY → AUTHORIZED 성공")
    void authorize_success() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx-123");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(p.getPgTransactionId()).isEqualTo("pg-tx-123");
    }

    @Test @DisplayName("AUTHORIZED가 아닌 상태에서 authorize 실패")
    void authorize_fail_wrongStatus() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-1");
        assertThatThrownBy(() -> p.authorize("pg-2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("AUTHORIZED → CAPTURED 성공")
    void capture_success() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-tx");
        p.capture();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getCapturedAt()).isNotNull();
    }

    @Test @DisplayName("READY에서 capture 실패")
    void capture_fail_notAuthorized() {
        PaymentDomain p = createReadyPayment();
        assertThatThrownBy(p::capture).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("CAPTURED → REFUNDED 성공")
    void refund_success() {
        PaymentDomain p = createCapturedPayment();
        p.refund();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test @DisplayName("READY에서 refund 실패")
    void refund_fail_notCaptured() {
        PaymentDomain p = createReadyPayment();
        assertThatThrownBy(p::refund).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("환불 가능 금액 계산")
    void refundableAmount() {
        PaymentDomain p = createCapturedPayment();
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("10000");
        p.addRefundedAmount(new BigDecimal("3000"));
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("7000");
    }

    @Test @DisplayName("전액 환불 여부")
    void fullyRefunded() {
        PaymentDomain p = createCapturedPayment();
        assertThat(p.isFullyRefunded()).isFalse();
        p.addRefundedAmount(new BigDecimal("10000"));
        assertThat(p.isFullyRefunded()).isTrue();
    }

    @Test @DisplayName("부분 환불 누적")
    void addRefundedAmount() {
        PaymentDomain p = createCapturedPayment();
        p.addRefundedAmount(new BigDecimal("2000"));
        p.addRefundedAmount(new BigDecimal("3000"));
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("5000");
    }

    @Test @DisplayName("전체 생명주기: READY → AUTHORIZED → CAPTURED → REFUNDED")
    void fullLifecycle() {
        PaymentDomain p = createReadyPayment();
        p.authorize("pg-123");
        p.capture();
        p.refund();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test @DisplayName("복원 생성자")
    void reconstitution() {
        PaymentDomain p = new PaymentDomain(1L, 2L, new BigDecimal("5000"), new BigDecimal("1000"),
                PaymentStatus.CAPTURED, "CARD", "pg-tx", null, null, null);
        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getRefundedAmount()).isEqualByComparingTo("1000");
        assertThat(p.getRefundableAmount()).isEqualByComparingTo("4000");
    }
}
