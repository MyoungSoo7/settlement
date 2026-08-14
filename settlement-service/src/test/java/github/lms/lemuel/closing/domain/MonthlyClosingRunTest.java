package github.lms.lemuel.closing.domain;

import github.lms.lemuel.closing.domain.exception.ClosingInvariantViolationException;
import github.lms.lemuel.closing.domain.exception.InvalidClosingRunStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthlyClosingRunTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    private static ClosingTotals totals(String gross, String refunded, String commission,
                                        String holdback, String net) {
        return ClosingTotals.of(new BigDecimal(gross), new BigDecimal(refunded),
                new BigDecimal(commission), new BigDecimal(holdback), new BigDecimal(net));
    }

    // ── start ──────────────────────────────────────────────────────────

    @Test
    void start_는_RUNNING_으로_시작하고_집계_스냅샷은_아직_없다() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);

        assertThat(run.getStatus()).isEqualTo(ClosingRunStatus.RUNNING);
        assertThat(run.getPeriodYm()).isEqualTo("2026-07");
        assertThat(run.getTriggeredBy()).isEqualTo("admin");
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getTotals()).isNull();
        assertThat(run.getFailureReason()).isNull();
    }

    @Test
    void start_는_period_없이_불가() {
        assertThatThrownBy(() -> MonthlyClosingRun.start(null, "admin", AUGUST))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void start_는_당월_마감_불가_완결되지_않은_월이다() {
        assertThatThrownBy(() -> MonthlyClosingRun.start(AUGUST, "admin", AUGUST))
                .isInstanceOf(ClosingInvariantViolationException.class)
                .hasMessageContaining("완결");
    }

    @Test
    void start_는_미래월_마감_불가() {
        assertThatThrownBy(() -> MonthlyClosingRun.start(YearMonth.of(2026, 9), "admin", AUGUST))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void start_는_triggeredBy_공백_불가() {
        assertThatThrownBy(() -> MonthlyClosingRun.start(JULY, " ", AUGUST))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void start_는_현재월_기준_없이_불가() {
        assertThatThrownBy(() -> MonthlyClosingRun.start(JULY, "admin", null))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    // ── complete ───────────────────────────────────────────────────────

    @Test
    void complete_는_집계_스냅샷을_못박고_COMPLETED_로_전이한다() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);

        run.complete(2, 10, 1, 3, totals("100000.00", "5000.00", "3500.00", "30000.00", "91500.00"));

        assertThat(run.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
        assertThat(run.getSellerCount()).isEqualTo(2);
        assertThat(run.getSettlementCount()).isEqualTo(10);
        assertThat(run.getUnmappedCount()).isEqualTo(1);
        assertThat(run.getPendingCount()).isEqualTo(3);
        assertThat(run.getTotals().grossAmount()).isEqualByComparingTo("100000.00");
        assertThat(run.getTotals().netAmount()).isEqualByComparingTo("91500.00");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void complete_는_거래_없는_월도_0건_0원으로_마감할_수_있다() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);

        run.complete(0, 0, 0, 0, totals("0", "0", "0", "0", "0"));

        assertThat(run.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
        assertThat(run.getTotals().grossAmount()).isEqualByComparingTo("0");
    }

    @Test
    void complete_는_totals_없이_불가() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);

        assertThatThrownBy(() -> run.complete(1, 1, 0, 0, null))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void complete_는_음수_건수_불가() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);
        ClosingTotals t = totals("0", "0", "0", "0", "0");

        assertThatThrownBy(() -> run.complete(-1, 0, 0, 0, t))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> run.complete(0, -1, 0, 0, t))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> run.complete(0, 0, -1, 0, t))
                .isInstanceOf(ClosingInvariantViolationException.class);
        assertThatThrownBy(() -> run.complete(0, 0, 0, -1, t))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void complete_는_이미_종결된_run_에서_불가() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);
        ClosingTotals t = totals("0", "0", "0", "0", "0");
        run.complete(0, 0, 0, 0, t);

        assertThatThrownBy(() -> run.complete(0, 0, 0, 0, t))
                .isInstanceOf(InvalidClosingRunStateException.class);
    }

    // ── fail ───────────────────────────────────────────────────────────

    @Test
    void fail_은_사유를_남기고_FAILED_로_전이한다() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "scheduler", AUGUST);

        run.fail("집계 쿼리 타임아웃");

        assertThat(run.getStatus()).isEqualTo(ClosingRunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("집계 쿼리 타임아웃");
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getTotals()).isNull();
    }

    @Test
    void fail_은_사유_공백_불가() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "scheduler", AUGUST);

        assertThatThrownBy(() -> run.fail(" "))
                .isInstanceOf(ClosingInvariantViolationException.class);
    }

    @Test
    void fail_은_이미_종결된_run_에서_불가() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "scheduler", AUGUST);
        run.fail("first");

        assertThatThrownBy(() -> run.fail("second"))
                .isInstanceOf(InvalidClosingRunStateException.class);
    }

    // ── id ─────────────────────────────────────────────────────────────

    @Test
    void assignId_는_1회만_가능() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", AUGUST);
        run.assignId(1L);

        assertThat(run.getId()).isEqualTo(1L);
        assertThatThrownBy(() -> run.assignId(2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rehydrate_는_저장된_상태를_그대로_복원한다() {
        ClosingTotals t = totals("100.00", "0", "3.50", "30.00", "96.50");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        MonthlyClosingRun run = MonthlyClosingRun.rehydrate(7L, JULY, ClosingRunStatus.COMPLETED,
                "admin", now, now, 1, 1, 0, 0, t, null, now);

        assertThat(run.getId()).isEqualTo(7L);
        assertThat(run.isCompleted()).isTrue();
        assertThat(run.getTotals().netAmount()).isEqualByComparingTo("96.50");
    }
}
