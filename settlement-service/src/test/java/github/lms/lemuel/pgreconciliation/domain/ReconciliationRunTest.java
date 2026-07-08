package github.lms.lemuel.pgreconciliation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReconciliationRun — PG 대사 실행 집합 루트")
class ReconciliationRunTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 3, 10);

    private ReconciliationRun running() {
        return ReconciliationRun.start("TOSS", TARGET, "toss-20260310.csv", "op-1");
    }

    private ReconciliationDiscrepancy pending() {
        return ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 100L, "PG-100",
                new BigDecimal("10000"), new BigDecimal("9500"));
    }

    private ReconciliationDiscrepancy autoCorrected() {
        return ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.ROUNDING_DIFF, 101L, "PG-101",
                new BigDecimal("10000.4"), new BigDecimal("10000"));
    }

    @Test
    @DisplayName("start: RUNNING 상태로 시작하고 메타데이터를 보존")
    void start_initializesRunning() {
        ReconciliationRun run = running();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.RUNNING);
        assertThat(run.getPgProvider()).isEqualTo("TOSS");
        assertThat(run.getTargetDate()).isEqualTo(TARGET);
        assertThat(run.getFileName()).isEqualTo("toss-20260310.csv");
        assertThat(run.getOperatorId()).isEqualTo("op-1");
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getDiscrepancies()).isEmpty();
    }

    @Test
    @DisplayName("complete: 집계 반영 후 COMPLETED — 자동보정과 검토대상을 분리 카운트")
    void complete_countsDiscrepancies() {
        ReconciliationRun run = running();

        run.complete(50, 48, 46, List.of(pending(), autoCorrected()));

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getTotalPgRows()).isEqualTo(50);
        assertThat(run.getTotalInternalRows()).isEqualTo(48);
        assertThat(run.getMatchedCount()).isEqualTo(46);
        assertThat(run.getDiscrepancyCount()).isEqualTo(1);
        assertThat(run.getAutoCorrectedCount()).isEqualTo(1);
        assertThat(run.getDiscrepancies()).hasSize(2);
    }

    @Test
    @DisplayName("complete: RUNNING 이 아니면 예외")
    void complete_notRunning_throws() {
        ReconciliationRun run = running();
        run.complete(1, 1, 1, List.of());

        assertThatThrownBy(() -> run.complete(1, 1, 1, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    @DisplayName("fail: FAILED 상태로 마감하고 사유를 note 에 기록")
    void fail_setsReason() {
        ReconciliationRun run = running();

        run.fail("파일 파싱 오류");

        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getNote()).isEqualTo("파일 파싱 오류");
    }

    @Test
    @DisplayName("assignId: 최초 1회만 부여 가능")
    void assignId_onlyOnce() {
        ReconciliationRun run = running();

        run.assignId(7L);
        assertThat(run.getId()).isEqualTo(7L);

        assertThatThrownBy(() -> run.assignId(8L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1회만");
    }

    @Test
    @DisplayName("rehydrate: 저장소 복원용 팩토리 — null discrepancies 는 빈 목록으로")
    void rehydrate_nullDiscrepancies() {
        ReconciliationRun run = ReconciliationRun.rehydrate(
                5L, "TOSS", TARGET, "f.csv", ReconciliationRunStatus.COMPLETED,
                null, null, 10, 10, 9, 1, 0, "op-1", "ok", null);

        assertThat(run.getId()).isEqualTo(5L);
        assertThat(run.getDiscrepancies()).isEmpty();
    }
}
