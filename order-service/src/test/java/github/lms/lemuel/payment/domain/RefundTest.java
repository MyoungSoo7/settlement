package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class RefundTest {

    @Test @DisplayName("request: 유효한 파라미터로 환불 요청 생성")
    void request_valid() {
        Refund refund = Refund.request(1L, new BigDecimal("5000"), "idem-key-1", "고객 변심");
        assertThat(refund.getPaymentId()).isEqualTo(1L);
        assertThat(refund.getAmount()).isEqualByComparingTo("5000");
        assertThat(refund.getIdempotencyKey()).isEqualTo("idem-key-1");
        assertThat(refund.getReason()).isEqualTo("고객 변심");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.REQUESTED);
        assertThat(refund.getRequestedAt()).isNotNull();
        assertThat(refund.isCompleted()).isFalse();
    }

    @Test @DisplayName("request: null paymentId이면 예외")
    void request_nullPaymentId() {
        assertThatThrownBy(() -> Refund.request(null, BigDecimal.TEN, "key", "이유"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentId required");
    }

    @Test @DisplayName("request: 0 이하 금액이면 예외")
    void request_zeroAmount() {
        assertThatThrownBy(() -> Refund.request(1L, BigDecimal.ZERO, "key", "이유"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be > 0");
    }

    @Test @DisplayName("request: null 금액이면 예외")
    void request_nullAmount() {
        assertThatThrownBy(() -> Refund.request(1L, null, "key", "이유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("request: blank idempotencyKey이면 예외")
    void request_blankKey() {
        assertThatThrownBy(() -> Refund.request(1L, BigDecimal.TEN, "  ", "이유"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey required");
    }

    @Test @DisplayName("markCompleted: REQUESTED → COMPLETED")
    void markCompleted() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markCompleted();
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.COMPLETED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(refund.isCompleted()).isTrue();
    }

    @Test @DisplayName("markCompleted: COMPLETED 상태에서 다시 호출하면 예외")
    void markCompleted_alreadyCompleted() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markCompleted();
        assertThatThrownBy(refund::markCompleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only REQUESTED");
    }

    @Test @DisplayName("markFailed: REQUESTED → FAILED")
    void markFailed() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markFailed("PG 오류");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.FAILED);
        assertThat(refund.getReason()).isEqualTo("PG 오류");
    }

    @Test @DisplayName("markFailed: COMPLETED 상태에서 호출하면 예외")
    void markFailed_fromCompleted() {
        Refund refund = Refund.request(1L, BigDecimal.TEN, "key", "이유");
        refund.markCompleted();
        assertThatThrownBy(() -> refund.markFailed("실패"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("Status enum: 3개의 값")
    void statusEnum() {
        assertThat(Refund.Status.values()).containsExactly(
                Refund.Status.REQUESTED, Refund.Status.COMPLETED, Refund.Status.FAILED);
    }
}
