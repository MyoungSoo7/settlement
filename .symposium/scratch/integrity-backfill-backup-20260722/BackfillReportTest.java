package github.lms.lemuel.integrity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackfillReportTest {

    @Test
    @DisplayName("of — 잔여 0 이면 complete, 필드 보존")
    void ofComputesCompleteWhenNothingRemains() {
        BackfillReport report = BackfillReport.of(2, 1, 0, 3);

        assertThat(report.created()).isEqualTo(2);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.remaining()).isZero();
        assertThat(report.pagesProcessed()).isEqualTo(3);
        assertThat(report.complete()).isTrue();
    }

    @Test
    @DisplayName("of — 잔여가 남으면 complete=false")
    void ofIsIncompleteWhileCandidatesRemain() {
        BackfillReport report = BackfillReport.of(0, 0, 5, 1);

        assertThat(report.remaining()).isEqualTo(5);
        assertThat(report.complete()).isFalse();
    }
}
