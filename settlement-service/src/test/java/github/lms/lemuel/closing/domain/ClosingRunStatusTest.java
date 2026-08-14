package github.lms.lemuel.closing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingRunStatusTest {

    @Test
    void RUNNING_은_COMPLETED_와_FAILED_로만_전이한다() {
        assertThat(ClosingRunStatus.RUNNING.canTransitionTo(ClosingRunStatus.COMPLETED)).isTrue();
        assertThat(ClosingRunStatus.RUNNING.canTransitionTo(ClosingRunStatus.FAILED)).isTrue();
        assertThat(ClosingRunStatus.RUNNING.canTransitionTo(ClosingRunStatus.RUNNING)).isFalse();
    }

    @Test
    void COMPLETED_는_종결_상태다() {
        assertThat(ClosingRunStatus.COMPLETED.canTransitionTo(ClosingRunStatus.RUNNING)).isFalse();
        assertThat(ClosingRunStatus.COMPLETED.canTransitionTo(ClosingRunStatus.FAILED)).isFalse();
        assertThat(ClosingRunStatus.COMPLETED.canTransitionTo(ClosingRunStatus.COMPLETED)).isFalse();
    }

    @Test
    void FAILED_는_종결_상태다_재실행은_새_run_생성이_정식_경로() {
        assertThat(ClosingRunStatus.FAILED.canTransitionTo(ClosingRunStatus.RUNNING)).isFalse();
        assertThat(ClosingRunStatus.FAILED.canTransitionTo(ClosingRunStatus.COMPLETED)).isFalse();
        assertThat(ClosingRunStatus.FAILED.canTransitionTo(ClosingRunStatus.FAILED)).isFalse();
    }
}
