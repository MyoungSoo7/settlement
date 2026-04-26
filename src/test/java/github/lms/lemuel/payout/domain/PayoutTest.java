package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class PayoutTest {

    @Nested
    @DisplayName("Payout 생성")
    class Creation {
        @Test
        @DisplayName("정상: 양수 amount + sellerId/settlementId 있으면 PENDING으로 생성")
        void create_ok() {
            Payout payout = Payout.request(10L, 42L, new BigDecimal("97000"));

            assertThat(payout.getSettlementId()).isEqualTo(10L);
            assertThat(payout.getSellerId()).isEqualTo(42L);
            assertThat(payout.getAmount()).isEqualByComparingTo("97000");
            assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(payout.getRequestedAt()).isNotNull();
        }

        @Test
        @DisplayName("amount 0/음수 거부")
        void amount_must_be_positive() {
            assertThatThrownBy(() -> Payout.request(10L, 42L, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Payout.request(10L, 42L, new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("settlementId/sellerId 누락 거부")
        void ids_required() {
            assertThatThrownBy(() -> Payout.request(null, 42L, new BigDecimal("97000")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Payout.request(10L, null, new BigDecimal("97000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("상태 머신")
    class StateMachine {
        @Test
        @DisplayName("PENDING → SUCCEEDED with bankTransactionId")
        void mark_succeeded() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markSucceeded("BANK-TX-001");
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
            assertThat(p.getBankTransactionId()).isEqualTo("BANK-TX-001");
            assertThat(p.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("PENDING → FAILED with reason")
        void mark_failed() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markFailed("계좌 정보 오류");
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.FAILED);
            assertThat(p.getFailureReason()).isEqualTo("계좌 정보 오류");
        }

        @Test
        @DisplayName("이미 SUCCEEDED 상태에서 markSucceeded 거부")
        void cannot_succeed_twice() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markSucceeded("BANK-TX-001");
            assertThatThrownBy(() -> p.markSucceeded("BANK-TX-002"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("FAILED → 재시도 (PENDING으로 복귀)")
        void retry_from_failed() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            p.markFailed("일시 오류");
            p.retry();
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(p.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("PENDING에서 retry 거부")
        void retry_only_from_failed() {
            Payout p = Payout.request(10L, 42L, new BigDecimal("97000"));
            assertThatThrownBy(p::retry).isInstanceOf(IllegalStateException.class);
        }
    }
}
