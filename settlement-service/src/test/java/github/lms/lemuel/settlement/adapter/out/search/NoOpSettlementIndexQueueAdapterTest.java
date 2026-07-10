package github.lms.lemuel.settlement.adapter.out.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;

class NoOpSettlementIndexQueueAdapterTest {

    private final NoOpSettlementIndexQueueAdapter adapter = new NoOpSettlementIndexQueueAdapter();

    @Test
    @DisplayName("enqueueForRetry — 아무 것도 하지 않고 예외 없이 반환")
    void enqueueForRetry_noOp() {
        assertThatNoException().isThrownBy(() -> adapter.enqueueForRetry(1L, "INDEX"));
    }
}
