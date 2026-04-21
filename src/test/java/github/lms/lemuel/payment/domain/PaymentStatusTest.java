package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class PaymentStatusTest {

    @Test @DisplayName("모든 상태값 존재") void allStatuses() {
        assertThat(PaymentStatus.values()).containsExactlyInAnyOrder(
                PaymentStatus.READY, PaymentStatus.AUTHORIZED,
                PaymentStatus.CAPTURED, PaymentStatus.FAILED,
                PaymentStatus.CANCELED, PaymentStatus.REFUNDED
        );
    }

    @Test @DisplayName("valueOf 동작") void valueOf() {
        assertThat(PaymentStatus.valueOf("READY")).isEqualTo(PaymentStatus.READY);
        assertThat(PaymentStatus.valueOf("CAPTURED")).isEqualTo(PaymentStatus.CAPTURED);
    }
}
