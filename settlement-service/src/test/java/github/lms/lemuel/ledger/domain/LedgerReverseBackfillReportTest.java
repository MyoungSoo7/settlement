package github.lms.lemuel.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LedgerReverseBackfillReport — 팩토리 및 불변 속성")
class LedgerReverseBackfillReportTest {

    @Test
    @DisplayName("of: totalEnqueued = enqueuedChargeback + enqueuedReconciliation")
    void ofTotalEnqueuedIsSum() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.of(200, 3, 7, 0L, 2);
        assertThat(report.totalEnqueued()).isEqualTo(10);
    }

    @Test
    @DisplayName("of: remainingMissing=0 이면 complete=true")
    void ofCompleteWhenZeroRemaining() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.of(200, 5, 0, 0L, 1);
        assertThat(report.complete()).isTrue();
    }

    @Test
    @DisplayName("of: remainingMissing>0 이면 complete=false")
    void ofIncompleteWhenRemainingPositive() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.of(200, 3, 2, 4L, 2);
        assertThat(report.complete()).isFalse();
        assertThat(report.remainingMissing()).isEqualTo(4);
    }

    @Test
    @DisplayName("of: notes 에 집계 정보가 포함된다")
    void ofNotesContainStats() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.of(100, 2, 3, 0L, 1);
        assertThat(report.notes()).isNotEmpty();
        String firstNote = report.notes().get(0);
        assertThat(firstNote).contains("차지백 2건");
        assertThat(firstNote).contains("PG대사 3건");
        assertThat(firstNote).contains("합계 5건");
    }

    @Test
    @DisplayName("status: totalEnqueued=0, pagesCommitted=0")
    void statusHasZeroEnqueued() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.status(3L);
        assertThat(report.totalEnqueued()).isZero();
        assertThat(report.pagesCommitted()).isZero();
        assertThat(report.remainingMissing()).isEqualTo(3);
        assertThat(report.complete()).isFalse();
    }

    @Test
    @DisplayName("status: remainingMissing=0 이면 complete=true")
    void statusCompleteWhenZero() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.status(0L);
        assertThat(report.complete()).isTrue();
    }

    @Test
    @DisplayName("notes 목록은 불변이다")
    void notesAreImmutable() {
        LedgerReverseBackfillReport report = LedgerReverseBackfillReport.of(200, 1, 1, 0L, 1);
        assertThat(report.notes()).isUnmodifiable();
    }
}
