package github.lms.lemuel.payout.domain;

import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutBounceTest {

    @Test
    @DisplayName("record: payoutId·reason 보존, 아직 미해소(resolvedPayoutId=null)")
    void record() {
        PayoutBounce b = PayoutBounce.record(500L, "ACCOUNT_CLOSED", "operator-1");

        assertThat(b.getPayoutId()).isEqualTo(500L);
        assertThat(b.getReason()).isEqualTo("ACCOUNT_CLOSED");
        assertThat(b.getOperatorId()).isEqualTo("operator-1");
        assertThat(b.getResolvedPayoutId()).isNull();
        assertThat(b.isResolved()).isFalse();
        assertThat(b.getBouncedAt()).isNotNull();
        assertThat(b.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("record: payoutId 누락 또는 사유 공백이면 타입 예외")
    void record_rejectsInvalid() {
        assertThatThrownBy(() -> PayoutBounce.record(null, "reason", "op"))
                .isInstanceOf(PayoutInvariantViolationException.class);
        assertThatThrownBy(() -> PayoutBounce.record(500L, " ", "op"))
                .isInstanceOf(PayoutInvariantViolationException.class);
        assertThatThrownBy(() -> PayoutBounce.record(500L, null, "op"))
                .isInstanceOf(PayoutInvariantViolationException.class);
    }

    @Test
    @DisplayName("resolveWith: 재발행 payout 링크 (set-once)")
    void resolveWith() {
        PayoutBounce b = PayoutBounce.record(500L, "ACCOUNT_CLOSED", "op");

        b.resolveWith(999L);

        assertThat(b.getResolvedPayoutId()).isEqualTo(999L);
        assertThat(b.isResolved()).isTrue();
    }

    @Test
    @DisplayName("resolveWith: 이미 해소된 반송에 재해소 시도 → 이중 재발행 차단 (타입 예외)")
    void resolveWith_rejectsSecondResolve() {
        PayoutBounce b = PayoutBounce.record(500L, "ACCOUNT_CLOSED", "op");
        b.resolveWith(999L);

        assertThatThrownBy(() -> b.resolveWith(1000L))
                .isInstanceOf(PayoutInvariantViolationException.class);
        assertThat(b.getResolvedPayoutId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("resolveWith: null 링크 거부")
    void resolveWith_rejectsNull() {
        PayoutBounce b = PayoutBounce.record(500L, "ACCOUNT_CLOSED", "op");
        assertThatThrownBy(() -> b.resolveWith(null))
                .isInstanceOf(PayoutInvariantViolationException.class);
    }

    @Test
    @DisplayName("rehydrate: 저장된 상태(해소 포함)를 그대로 복원")
    void rehydrate() {
        LocalDateTime ts = LocalDateTime.of(2026, 7, 20, 9, 0);
        PayoutBounce b = PayoutBounce.rehydrate(1L, 500L, "ACCOUNT_CLOSED", 999L, "op", ts, ts);

        assertThat(b.getId()).isEqualTo(1L);
        assertThat(b.getResolvedPayoutId()).isEqualTo(999L);
        assertThat(b.isResolved()).isTrue();
    }
}
