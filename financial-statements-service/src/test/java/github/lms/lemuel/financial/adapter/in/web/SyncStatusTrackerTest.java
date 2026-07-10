package github.lms.lemuel.financial.adapter.in.web;

import github.lms.lemuel.financial.adapter.in.web.SyncStatusTracker.State;
import github.lms.lemuel.financial.application.port.in.SyncResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SyncStatusTracker — 단일 실행 상태보드의 IDLE→RUNNING→DONE/FAILED 전이와
 * 동시 선점(이미 RUNNING 이면 tryStart=false)을 검증.
 */
class SyncStatusTrackerTest {

    @Test
    @DisplayName("초기 상태는 IDLE")
    void initialIdle() {
        SyncStatusTracker tracker = new SyncStatusTracker();
        assertThat(tracker.current().state()).isEqualTo(State.IDLE);
        assertThat(tracker.current().job()).isNull();
    }

    @Test
    @DisplayName("tryStart → RUNNING 선점, 실행 중 재선점은 거부")
    void tryStartClaimsAndBlocks() {
        SyncStatusTracker tracker = new SyncStatusTracker();

        assertThat(tracker.tryStart("companies")).isTrue();
        assertThat(tracker.current().state()).isEqualTo(State.RUNNING);
        assertThat(tracker.current().job()).isEqualTo("companies");
        assertThat(tracker.current().startedAt()).isNotNull();

        assertThat(tracker.tryStart("statements-2024")).isFalse();
    }

    @Test
    @DisplayName("complete → DONE + 결과 보존")
    void complete() {
        SyncStatusTracker tracker = new SyncStatusTracker();
        tracker.tryStart("companies");
        SyncResult result = new SyncResult(10, 8, 8, 2);

        tracker.complete(result);

        assertThat(tracker.current().state()).isEqualTo(State.DONE);
        assertThat(tracker.current().result()).isEqualTo(result);
        assertThat(tracker.current().finishedAt()).isNotNull();
        assertThat(tracker.current().error()).isNull();

        // DONE 이후 다시 tryStart 가능
        assertThat(tracker.tryStart("statements-2024")).isTrue();
    }

    @Test
    @DisplayName("fail → FAILED + 에러 메시지 보존")
    void fail() {
        SyncStatusTracker tracker = new SyncStatusTracker();
        tracker.tryStart("companies");

        tracker.fail("DART timeout");

        assertThat(tracker.current().state()).isEqualTo(State.FAILED);
        assertThat(tracker.current().error()).isEqualTo("DART timeout");
        assertThat(tracker.current().result()).isNull();
    }
}
