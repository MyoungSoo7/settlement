package github.lms.lemuel.integrity.domain;

import github.lms.lemuel.integrity.domain.ProjectionDiffReport.AmountMismatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProjectionDiffReport — INV-12 리포트 판정")
class ProjectionDiffReportTest {

    private final LocalDate date = LocalDate.of(2026, 6, 17);

    @Test
    @DisplayName("matched — 체크섬 일치는 ok=true·빈 diff")
    void matched() {
        ProjectionDiffReport r = ProjectionDiffReport.matched(date, "payment", 5L, new BigDecimal("5000"));
        assertThat(r.ok()).isTrue();
        assertThat(r.checksumMatched()).isTrue();
        assertThat(r.missingInProjectionIds()).isEmpty();
        assertThat(r.orphanInProjectionIds()).isEmpty();
        assertThat(r.reasons()).isEmpty();
    }

    @Test
    @DisplayName("of — 누락 있으면 ok=false·INV-12 사유·누락 id 노출")
    void missingMakesNotOk() {
        ProjectionDiffReport r = ProjectionDiffReport.of(date, "payment",
                3L, new BigDecimal("3000"), 2L, new BigDecimal("2000"),
                List.of(902L), new BigDecimal("1000"), 1L,
                List.of(), 0L, List.of(), 0L, false);
        assertThat(r.ok()).isFalse();
        assertThat(r.checksumMatched()).isFalse();
        assertThat(r.missingInProjectionIds()).containsExactly(902L);
        assertThat(r.reasons()).anySatisfy(s -> assertThat(s).contains("INV-12"));
    }

    @Test
    @DisplayName("of — 고아·금액불일치도 각각 사유로 노출")
    void orphanAndMismatchReasons() {
        ProjectionDiffReport r = ProjectionDiffReport.of(date, "payment",
                2L, new BigDecimal("2000"), 3L, new BigDecimal("2900"),
                List.of(), BigDecimal.ZERO, 0L,
                List.of(9L), 1L,
                List.of(new AmountMismatch(1L, new BigDecimal("1000"), new BigDecimal("900"))), 1L, false);
        assertThat(r.ok()).isFalse();
        assertThat(r.orphanInProjectionCount()).isEqualTo(1L);
        assertThat(r.amountMismatchCount()).isEqualTo(1L);
        assertThat(r.reasons()).hasSize(2);
    }

    @Test
    @DisplayName("of — 절단만 있고 구체 위반 없으면 ok=true(절단 경고만)")
    void truncatedOnlyIsOk() {
        ProjectionDiffReport r = ProjectionDiffReport.of(date, "payment",
                0L, BigDecimal.ZERO, 0L, BigDecimal.ZERO,
                List.of(), BigDecimal.ZERO, 0L,
                List.of(), 0L, List.of(), 0L, true);
        assertThat(r.ok()).isTrue();
        assertThat(r.reasons()).anySatisfy(s -> assertThat(s).contains("절단"));
    }
}
