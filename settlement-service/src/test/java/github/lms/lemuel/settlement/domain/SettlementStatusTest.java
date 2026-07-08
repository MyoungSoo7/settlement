package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SettlementStatus — 상태 전이/복원 규칙")
class SettlementStatusTest {

    @Test
    @DisplayName("fromString: 유효 값은 대소문자 무관하게 복원")
    void fromString_valid() {
        assertThat(SettlementStatus.fromString("done")).isEqualTo(SettlementStatus.DONE);
        assertThat(SettlementStatus.fromString("PROCESSING")).isEqualTo(SettlementStatus.PROCESSING);
    }

    @Test
    @DisplayName("fromString: null 또는 알 수 없는 값은 REQUESTED 로 폴백")
    void fromString_fallback() {
        assertThat(SettlementStatus.fromString(null)).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(SettlementStatus.fromString("PENDING")).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(SettlementStatus.fromString("")).isEqualTo(SettlementStatus.REQUESTED);
    }

    @Test
    @DisplayName("canTransitionTo: REQUESTED 는 PROCESSING·CANCELED 로만 전이 가능")
    void canTransition_fromRequested() {
        assertThat(SettlementStatus.REQUESTED.canTransitionTo(SettlementStatus.PROCESSING)).isTrue();
        assertThat(SettlementStatus.REQUESTED.canTransitionTo(SettlementStatus.CANCELED)).isTrue();
        assertThat(SettlementStatus.REQUESTED.canTransitionTo(SettlementStatus.DONE)).isFalse();
    }

    @Test
    @DisplayName("canTransitionTo: PROCESSING 은 DONE·FAILED 로만 전이 가능")
    void canTransition_fromProcessing() {
        assertThat(SettlementStatus.PROCESSING.canTransitionTo(SettlementStatus.DONE)).isTrue();
        assertThat(SettlementStatus.PROCESSING.canTransitionTo(SettlementStatus.FAILED)).isTrue();
        assertThat(SettlementStatus.PROCESSING.canTransitionTo(SettlementStatus.REQUESTED)).isFalse();
    }

    @Test
    @DisplayName("canTransitionTo: FAILED 는 REQUESTED 재시도만 허용")
    void canTransition_fromFailed() {
        assertThat(SettlementStatus.FAILED.canTransitionTo(SettlementStatus.REQUESTED)).isTrue();
        assertThat(SettlementStatus.FAILED.canTransitionTo(SettlementStatus.DONE)).isFalse();
    }

    @Test
    @DisplayName("canTransitionTo: DONE·CANCELED 는 종료 상태로 전이 불가")
    void canTransition_terminalStates() {
        assertThat(SettlementStatus.DONE.canTransitionTo(SettlementStatus.REQUESTED)).isFalse();
        assertThat(SettlementStatus.DONE.canTransitionTo(SettlementStatus.PROCESSING)).isFalse();
        assertThat(SettlementStatus.CANCELED.canTransitionTo(SettlementStatus.REQUESTED)).isFalse();
    }
}
