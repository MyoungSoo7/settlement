package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class RefundTest {

    @Nested
    @DisplayName("Refund 생성")
    class Creation {

        @Test
        @DisplayName("정상: 양수 금액 + idempotencyKey 있으면 REQUESTED 상태로 생성된다")
        void create_ok() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", "고객 변심");

            assertThat(refund.getPaymentId()).isEqualTo(10L);
            assertThat(refund.getAmount()).isEqualByComparingTo("3000");
            assertThat(refund.getIdempotencyKey()).isEqualTo("K1");
            assertThat(refund.getReason()).isEqualTo("고객 변심");
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(refund.getRequestedAt()).isNotNull();
        }

        @Test
        @DisplayName("에러: 0 또는 음수 금액은 거부")
        void create_amount_zero_or_negative() {
            assertThatThrownBy(() -> Refund.request(10L, BigDecimal.ZERO, "K1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than zero");
            assertThatThrownBy(() -> Refund.request(10L, new BigDecimal("-1"), "K1", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("에러: idempotencyKey 누락")
        void create_idempotency_required() {
            assertThatThrownBy(() -> Refund.request(10L, new BigDecimal("3000"), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Refund.request(10L, new BigDecimal("3000"), "  ", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("상태 머신")
    class StateMachine {

        @Test
        @DisplayName("REQUESTED → COMPLETED")
        void complete_from_requested() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markCompleted();
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(refund.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 COMPLETED 상태에서 markCompleted 재호출은 거부")
        void complete_twice_rejected() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markCompleted();
            assertThatThrownBy(refund::markCompleted)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("REQUESTED → FAILED")
        void fail_from_requested() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markFailed("PG timeout");
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        }

        @Test
        @DisplayName("FAILED 상태에서 markFailed 재호출 거부")
        void markFailed_twice_rejected() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markFailed("PG timeout");
            assertThatThrownBy(() -> refund.markFailed("again"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("FAILED 상태에서 markCompleted 거부")
        void markCompleted_after_failed_rejected() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markFailed("PG timeout");
            assertThatThrownBy(refund::markCompleted)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("markFailed: 원래 reason이 있으면 FAIL: 접두어와 결합")
        void markFailed_appends_to_existing_reason() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", "고객 변심");
            refund.markFailed("PG timeout");
            assertThat(refund.getReason()).isEqualTo("고객 변심 | FAIL: PG timeout");
        }

        @Test
        @DisplayName("markFailed: 원래 reason이 null이면 FAIL: 접두어만 적용")
        void markFailed_prefixes_when_reason_was_null() {
            Refund refund = Refund.request(10L, new BigDecimal("3000"), "K1", null);
            refund.markFailed("PG timeout");
            assertThat(refund.getReason()).isEqualTo("FAIL: PG timeout");
        }
    }
}
