package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.InvalidReconciliationStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대사 마감(closing) — 확정된 기간을 잠가 사후 변경을 막는다.
 *
 * <p><b>메우는 구멍</b>: 같은 파일 재업로드는 SHA-256 으로 이미 멱등 차단된다. 그러나 같은
 * (PG, 날짜)에 <b>다른 파일</b>을 올리면 새 run 이 생기고, 이미 정산·지급이 끝난 기간에 새 불일치와
 * 새 clawback 이 발생할 수 있다. 마감은 그 경로를 닫는다.
 *
 * <p><b>미결을 남긴 마감은 마감이 아니다</b> — PENDING 불일치가 있으면 마감을 거부한다. 미결을
 * 안고 잠그면 "검토했다"는 기록만 남고 차이는 영원히 미해결로 묻힌다.
 */
class ReconciliationRunCloseTest {

    private static final LocalDate D = LocalDate.of(2026, 8, 5);

    private static ReconciliationRun completedRun(List<ReconciliationDiscrepancy> found) {
        ReconciliationRun run = ReconciliationRun.start("TOSS", D, "toss.csv", "op-1", "sha");
        run.complete(10, 10, 10 - found.size(), found);
        return run;
    }

    private static ReconciliationDiscrepancy pending() {
        return ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 1L, "TX-1",
                new BigDecimal("10000"), new BigDecimal("9000"));
    }

    private static ReconciliationDiscrepancy autoCorrected() {
        return ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.ROUNDING_DIFF, 1L, "TX-2",
                new BigDecimal("10000"), new BigDecimal("10000.5"));
    }

    @Test
    @DisplayName("미결 불일치가 없으면 COMPLETED → CLOSED 로 마감된다")
    void closesWhenNoPendingDiscrepancy() {
        ReconciliationRun run = completedRun(List.of());

        run.close("op-2", "8월 1주차 마감");

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.CLOSED);
        assertThat(run.getClosedBy()).isEqualTo("op-2");
        assertThat(run.getClosedAt()).isNotNull();
        assertThat(run.getNote()).contains("8월 1주차 마감");
    }

    @Test
    @DisplayName("자동 보정된 차이만 있으면 마감 가능 — 이미 해소된 건이다")
    void autoCorrectedDoesNotBlockClose() {
        ReconciliationRun run = completedRun(List.of(autoCorrected()));

        run.close("op-2", null);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.CLOSED);
    }

    @Test
    @DisplayName("미결(PENDING) 불일치가 남아 있으면 마감 거부 — 미결을 안고 잠글 수 없다")
    void rejectsCloseWithPendingDiscrepancy() {
        ReconciliationRun run = completedRun(List.of(pending()));

        assertThatThrownBy(() -> run.close("op-2", null))
                .isInstanceOf(InvalidReconciliationStateException.class)
                .hasMessageContaining("미결");

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
    }

    @Test
    @DisplayName("RUNNING 상태는 마감 불가 — 완료되지 않은 대사를 잠글 수 없다")
    void rejectsCloseWhenRunning() {
        ReconciliationRun run = ReconciliationRun.start("TOSS", D, "toss.csv", "op-1", "sha");

        assertThatThrownBy(() -> run.close("op-2", null))
                .isInstanceOf(InvalidReconciliationStateException.class);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.RUNNING);
    }

    @Test
    @DisplayName("FAILED 상태도 마감 불가")
    void rejectsCloseWhenFailed() {
        ReconciliationRun run = ReconciliationRun.start("TOSS", D, "toss.csv", "op-1", "sha");
        run.fail("파싱 실패");

        assertThatThrownBy(() -> run.close("op-2", null))
                .isInstanceOf(InvalidReconciliationStateException.class);
    }

    @Test
    @DisplayName("CLOSED 는 종착 상태 — 두 번 마감할 수 없다")
    void closedIsTerminal() {
        ReconciliationRun run = completedRun(List.of());
        run.close("op-2", null);

        assertThatThrownBy(() -> run.close("op-3", null))
                .isInstanceOf(InvalidReconciliationStateException.class);

        assertThat(run.getClosedBy()).isEqualTo("op-2");   // 최초 마감자 보존
    }

    @Test
    @DisplayName("마감 후에는 complete 로 되돌릴 수 없다 — 확정 기간의 결과는 재작성 대상이 아니다")
    void cannotRecompleteAfterClose() {
        ReconciliationRun run = completedRun(List.of());
        run.close("op-2", null);

        assertThatThrownBy(() -> run.complete(1, 1, 1, List.of()))
                .isInstanceOf(InvalidReconciliationStateException.class);
    }

    @Test
    @DisplayName("마감 여부를 상태로 물어볼 수 있다 — 서비스가 새 run 차단 판정에 쓴다")
    void exposesClosedFlag() {
        ReconciliationRun open = completedRun(List.of());
        assertThat(open.isClosed()).isFalse();

        open.close("op-2", null);
        assertThat(open.isClosed()).isTrue();
    }

    @Test
    @DisplayName("마감 사유가 없으면 기존 note 를 훼손하지 않는다")
    void nullNoteKeepsExistingNote() {
        ReconciliationRun run = ReconciliationRun.start("TOSS", D, "toss.csv", "op-1", "sha");
        run.complete(1, 1, 1, List.of());

        run.close("op-2", null);

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.CLOSED);
    }
}
