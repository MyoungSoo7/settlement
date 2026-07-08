package github.lms.lemuel.payout.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PayoutConcurrentClaimException — 선점 경합 예외")
class PayoutConcurrentClaimExceptionTest {

    @Test
    @DisplayName("메시지에 payoutId 를 포함하고 RuntimeException 이다")
    void carriesPayoutIdInMessage() {
        PayoutConcurrentClaimException ex = new PayoutConcurrentClaimException(42L);

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).contains("42");
        assertThat(ex.getMessage()).contains("already claimed");
    }
}
