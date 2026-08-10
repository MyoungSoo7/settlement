package github.lms.lemuel.settlement.domain.rerun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재실행 결과 리포트 — 한 단계가 실패해도 나머지는 계속 실행되고, 결과가 단계별로 보존된다.
 *
 * <p>운영자가 "어느 step 이 실패했는지" 보고 그 step 만 다시 돌릴 수 있어야 하므로,
 * 전체 성공/실패 한 비트가 아니라 단계별 결과를 그대로 남긴다.
 */
class SettlementRerunReportTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 5);

    @Test
    @DisplayName("전 단계 성공이면 complete=true, 처리 건수는 합산")
    void allSucceeded() {
        SettlementRerunReport report = new SettlementRerunReport(TARGET, List.of(
                SettlementRerunReport.StepResult.succeeded(SettlementRerunScope.CONFIRM, 12, "confirmed=12"),
                SettlementRerunReport.StepResult.succeeded(SettlementRerunScope.HOLDBACK_RELEASE, 3, "released=3")
        ));

        assertThat(report.complete()).isTrue();
        assertThat(report.totalAffected()).isEqualTo(15);
        assertThat(report.failedSteps()).isEmpty();
    }

    @Test
    @DisplayName("한 단계 실패해도 다른 단계 결과는 보존 — complete=false, 실패 단계 식별 가능")
    void partialFailureKeepsOtherResults() {
        SettlementRerunReport report = new SettlementRerunReport(TARGET, List.of(
                SettlementRerunReport.StepResult.succeeded(SettlementRerunScope.CONFIRM, 12, "confirmed=12"),
                SettlementRerunReport.StepResult.failed(SettlementRerunScope.HOLDBACK_RELEASE, "DB timeout")
        ));

        assertThat(report.complete()).isFalse();
        assertThat(report.totalAffected()).isEqualTo(12);
        assertThat(report.failedSteps()).containsExactly(SettlementRerunScope.HOLDBACK_RELEASE);
    }

    @Test
    @DisplayName("실패 단계의 처리 건수는 0 으로 집계 — 실패를 성과로 오인하지 않는다")
    void failedStepCountsZero() {
        SettlementRerunReport.StepResult failed =
                SettlementRerunReport.StepResult.failed(SettlementRerunScope.CONFIRM, "boom");

        assertThat(failed.affected()).isZero();
        assertThat(failed.status()).isEqualTo(SettlementRerunReport.StepStatus.FAILED);
        assertThat(failed.detail()).contains("boom");
    }

    @Test
    @DisplayName("단계 목록은 방어적 복사 — 리포트 생성 후 외부 리스트 변경이 새어들지 않는다")
    void defensiveCopy() {
        var mutable = new java.util.ArrayList<SettlementRerunReport.StepResult>();
        mutable.add(SettlementRerunReport.StepResult.succeeded(SettlementRerunScope.CONFIRM, 1, "ok"));

        SettlementRerunReport report = new SettlementRerunReport(TARGET, mutable);
        mutable.clear();

        assertThat(report.steps()).hasSize(1);
    }
}
