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

    // 전이표 단일 출처 직접 검증 — PaymentDomain 애그리거트 전이 메서드가 허용하는 집합과 동일해야 한다.
    @Test @DisplayName("허용 전이만 canTransitionTo=true")
    void canTransitionTo_allowed() {
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CAPTURED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CANCELED)).isTrue();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
    }

    @Test @DisplayName("표에 없는 전이는 canTransitionTo=false")
    void canTransitionTo_disallowed() {
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.CANCELED)).isFalse();
        // 종료 상태는 어떤 전이도 불가
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.REFUNDED.canTransitionTo(target)).isFalse();
            assertThat(PaymentStatus.CANCELED.canTransitionTo(target)).isFalse();
            assertThat(PaymentStatus.FAILED.canTransitionTo(target)).isFalse();
        }
    }
}
