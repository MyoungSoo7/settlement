package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase.RecalcSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecalcStatusTrackerTest {

    private final RecalcStatusTracker tracker = new RecalcStatusTracker();

    @Test
    @DisplayName("초기 상태는 IDLE")
    void initialIdle() {
        assertEquals(RecalcStatusTracker.State.IDLE, tracker.current().state());
        assertNull(tracker.current().job());
    }

    @Test
    @DisplayName("tryStart 는 IDLE 에서 RUNNING 으로 선점")
    void tryStart() {
        assertTrue(tracker.tryStart("all"));
        assertEquals(RecalcStatusTracker.State.RUNNING, tracker.current().state());
        assertEquals("all", tracker.current().job());
        assertNotNull(tracker.current().startedAt());
    }

    @Test
    @DisplayName("RUNNING 중에는 tryStart 가 false")
    void tryStartWhileRunning() {
        tracker.tryStart("all");
        assertFalse(tracker.tryStart("other"));
    }

    @Test
    @DisplayName("complete 는 요약과 함께 DONE 으로 전이")
    void complete() {
        tracker.tryStart("all");
        RecalcSummary summary = new RecalcSummary(10, 7, 2, 1);
        tracker.complete(summary);

        assertEquals(RecalcStatusTracker.State.DONE, tracker.current().state());
        assertEquals(summary, tracker.current().result());
        assertNotNull(tracker.current().finishedAt());
        // DONE 이후 다시 선점 가능
        assertTrue(tracker.tryStart("again"));
    }

    @Test
    @DisplayName("fail 은 오류 메시지와 함께 FAILED 로 전이")
    void fail() {
        tracker.tryStart("all");
        tracker.fail("boom");

        assertEquals(RecalcStatusTracker.State.FAILED, tracker.current().state());
        assertEquals("boom", tracker.current().error());
        assertNull(tracker.current().result());
    }

    @Test
    @DisplayName("FAILED 이후에도 다시 선점 가능 — 다음 배치가 막히지 않는다")
    void restartAfterFail() {
        tracker.tryStart("all");
        tracker.fail("boom");

        assertTrue(tracker.tryStart("all"));
        assertEquals(RecalcStatusTracker.State.RUNNING, tracker.current().state());
    }
}
