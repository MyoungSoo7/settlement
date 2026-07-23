package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PayoutBackfillReport — 팩토리·완료 판정")
class PayoutBackfillReportTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 1, 31);

    @Test
    @DisplayName("of: remaining=0 이면 complete=true, notes 에 '완료' 포함")
    void of_remaining0_completeTrue() {
        var report = PayoutBackfillReport.of(FROM, TO, 100, 42, 3, 1, 0, 1);

        assertThat(report.complete()).isTrue();
        assertThat(report.remaining()).isZero();
        assertThat(report.created()).isEqualTo(42);
        assertThat(report.skipped()).isEqualTo(3);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.pagesCommitted()).isEqualTo(1);
        assertThat(report.notes()).anyMatch(n -> n.contains("완료"));
    }

    @Test
    @DisplayName("of: remaining>0 이면 complete=false, notes 에 '재실행 권장' 포함")
    void of_remainingPositive_completeFalse() {
        var report = PayoutBackfillReport.of(FROM, TO, 100, 5, 0, 2, 10, 2);

        assertThat(report.complete()).isFalse();
        assertThat(report.remaining()).isEqualTo(10);
        assertThat(report.notes()).anyMatch(n -> n.contains("재실행 권장"));
    }

    @Test
    @DisplayName("status: remaining=0 이면 complete=true, 백필 수치는 0")
    void status_remaining0() {
        var report = PayoutBackfillReport.status(FROM, TO, 0);

        assertThat(report.complete()).isTrue();
        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.pagesCommitted()).isZero();
    }

    @Test
    @DisplayName("status: remaining>0 이면 complete=false, notes 에 건수 포함")
    void status_remainingPositive() {
        var report = PayoutBackfillReport.status(FROM, TO, 7);

        assertThat(report.complete()).isFalse();
        assertThat(report.remaining()).isEqualTo(7);
        assertThat(report.notes()).anyMatch(n -> n.contains("7"));
    }

    @Test
    @DisplayName("of: notes 는 수정 불가 리스트")
    void of_notesImmutable() {
        var report = PayoutBackfillReport.of(FROM, TO, 100, 1, 0, 0, 0, 1);

        assertThat(report.notes()).isNotEmpty();
        // 수정 불가 — UnsupportedOperationException 발생
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> report.notes().add("hack"));
    }
}
