package github.lms.lemuel.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CashflowReport — 캐시플로우 리포트 불변식/기본값")
class CashflowReportTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @Test
    @DisplayName("정상 생성 시 필드가 보존된다")
    void of_happyPath() {
        CashflowReport report = CashflowReport.of(FROM, TO, BucketGranularity.DAY, List.of());

        assertThat(report.from()).isEqualTo(FROM);
        assertThat(report.to()).isEqualTo(TO);
        assertThat(report.granularity()).isEqualTo(BucketGranularity.DAY);
        assertThat(report.buckets()).isEmpty();
        assertThat(report.totals()).isNotNull();
        assertThat(report.reconciliation()).isNotNull();
    }

    @Test
    @DisplayName("canonical 생성자: null buckets/totals/reconciliation 은 기본값으로 채워진다")
    void compactConstructor_fillsDefaults() {
        CashflowReport report = new CashflowReport(FROM, TO, BucketGranularity.MONTH,
                null, null, null);

        assertThat(report.buckets()).isEmpty();
        assertThat(report.totals()).isNotNull();
        assertThat(report.reconciliation()).isNotNull();
    }

    @Test
    @DisplayName("from 또는 to 가 null 이면 예외")
    void nullRange_throws() {
        assertThatThrownBy(() -> CashflowReport.of(null, TO, BucketGranularity.DAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from/to are required");
        assertThatThrownBy(() -> CashflowReport.of(FROM, null, BucketGranularity.DAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("from 이 to 보다 이후면 예외")
    void reversedRange_throws() {
        assertThatThrownBy(() -> CashflowReport.of(TO, FROM, BucketGranularity.DAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be <= to");
    }

    @Test
    @DisplayName("granularity 가 null 이면 예외")
    void nullGranularity_throws() {
        assertThatThrownBy(() -> new CashflowReport(FROM, TO, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("granularity is required");
    }
}
