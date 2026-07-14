package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PayoutStatus — 전이 선언표(canTransitionTo)")
class PayoutStatusTest {

    @Test
    @DisplayName("REQUESTED 는 SENDING·CANCELED 로만 전이 가능")
    void fromRequested() {
        assertThat(PayoutStatus.REQUESTED.canTransitionTo(PayoutStatus.SENDING)).isTrue();
        assertThat(PayoutStatus.REQUESTED.canTransitionTo(PayoutStatus.CANCELED)).isTrue();
        assertThat(PayoutStatus.REQUESTED.canTransitionTo(PayoutStatus.COMPLETED)).isFalse();
        assertThat(PayoutStatus.REQUESTED.canTransitionTo(PayoutStatus.FAILED)).isFalse();
    }

    @Test
    @DisplayName("SENDING 은 COMPLETED·FAILED 로만 전이 가능 — CANCELED 불가(송금 진행 중)")
    void fromSending() {
        assertThat(PayoutStatus.SENDING.canTransitionTo(PayoutStatus.COMPLETED)).isTrue();
        assertThat(PayoutStatus.SENDING.canTransitionTo(PayoutStatus.FAILED)).isTrue();
        assertThat(PayoutStatus.SENDING.canTransitionTo(PayoutStatus.CANCELED)).isFalse();
        assertThat(PayoutStatus.SENDING.canTransitionTo(PayoutStatus.REQUESTED)).isFalse();
    }

    @Test
    @DisplayName("FAILED 는 REQUESTED(retry)·CANCELED 로만 전이 가능")
    void fromFailed() {
        assertThat(PayoutStatus.FAILED.canTransitionTo(PayoutStatus.REQUESTED)).isTrue();
        assertThat(PayoutStatus.FAILED.canTransitionTo(PayoutStatus.CANCELED)).isTrue();
        assertThat(PayoutStatus.FAILED.canTransitionTo(PayoutStatus.SENDING)).isFalse();
        assertThat(PayoutStatus.FAILED.canTransitionTo(PayoutStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("COMPLETED·CANCELED 는 종결 상태 — 어떤 전이도 불가")
    void terminalStates() {
        for (PayoutStatus target : PayoutStatus.values()) {
            assertThat(PayoutStatus.COMPLETED.canTransitionTo(target)).isFalse();
            assertThat(PayoutStatus.CANCELED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("전이 집합 전수 검증 — 허용된 전이 외에는 전부 불허")
    void exhaustiveMatrix() {
        Map<PayoutStatus, Set<PayoutStatus>> allowed = new EnumMap<>(PayoutStatus.class);
        allowed.put(PayoutStatus.REQUESTED, EnumSet.of(PayoutStatus.SENDING, PayoutStatus.CANCELED));
        allowed.put(PayoutStatus.SENDING, EnumSet.of(PayoutStatus.COMPLETED, PayoutStatus.FAILED));
        allowed.put(PayoutStatus.FAILED, EnumSet.of(PayoutStatus.REQUESTED, PayoutStatus.CANCELED));
        allowed.put(PayoutStatus.COMPLETED, EnumSet.noneOf(PayoutStatus.class));
        allowed.put(PayoutStatus.CANCELED, EnumSet.noneOf(PayoutStatus.class));

        for (PayoutStatus from : PayoutStatus.values()) {
            for (PayoutStatus to : PayoutStatus.values()) {
                boolean expected = allowed.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }
}
