package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.pgreconciliation.application.port.in.PreviewClawbackImpactUseCase.ClawbackImpact;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyStatus;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 대사 승인 전 회수 영향 미리보기.
 *
 * <p>승인 버튼은 셀러에게서 돈을 도로 가져오는 후속 역정산을 일으킨다. 지금은 눌러 봐야 얼마가
 * 회수되는지 알 수 있어, 운영자가 규모를 모른 채 확정하게 된다.
 */
@ExtendWith(MockitoExtension.class)
class PreviewClawbackImpactServiceTest {

    @Mock LoadReconciliationRunPort loadPort;
    @InjectMocks PreviewClawbackImpactService service;

    private ReconciliationDiscrepancy discrepancy(Long id, DiscrepancyType type, String internal,
                                                  String difference, DiscrepancyStatus status) {
        return ReconciliationDiscrepancy.rehydrate(id, 1L, type, 100L + id, "PG-" + id,
                new BigDecimal(internal), null,
                difference == null ? null : new BigDecimal(difference),
                status, null, null, null, LocalDateTime.now());
    }

    private ReconciliationRun runWith(List<ReconciliationDiscrepancy> discrepancies) {
        return ReconciliationRun.rehydrate(1L, "TOSS", LocalDate.of(2026, 8, 1), "f.csv",
                github.lms.lemuel.pgreconciliation.domain.ReconciliationRunStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now(),
                10, 10, 10 - discrepancies.size(), discrepancies.size(), 0, "op", null, discrepancies);
    }

    @Test @DisplayName("승인 대기 차이의 예상 회수 총액을 낸다")
    void sumsPendingClawbacks() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(runWith(List.of(
                discrepancy(1L, DiscrepancyType.AMOUNT_MISMATCH, "10000", "-300", DiscrepancyStatus.PENDING),
                discrepancy(2L, DiscrepancyType.MISSING_PG, "5000", null, DiscrepancyStatus.PENDING)))));

        ClawbackImpact impact = service.previewRun(1L);

        assertThat(impact.clawbackCount()).isEqualTo(2);
        assertThat(impact.totalClawbackAmount()).isEqualByComparingTo("5300");
    }

    @Test @DisplayName("회수 대상이 아닌 유형은 금액 0 으로 분리해 보여준다 — 승인해도 돈이 안 움직인다")
    void separatesNonClawbackTypes() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(runWith(List.of(
                discrepancy(1L, DiscrepancyType.FEE_MISMATCH, "10000", "-300", DiscrepancyStatus.PENDING),
                discrepancy(2L, DiscrepancyType.AMOUNT_MISMATCH, "10000", "300", DiscrepancyStatus.PENDING)))));

        ClawbackImpact impact = service.previewRun(1L);

        assertThat(impact.clawbackCount()).isZero();
        assertThat(impact.totalClawbackAmount()).isEqualByComparingTo("0");
        assertThat(impact.noImpactCount()).isEqualTo(2);
    }

    @Test @DisplayName("이미 처리된 차이는 제외한다 — 승인으로 새로 나갈 영향만 보여준다")
    void excludesAlreadyResolved() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(runWith(List.of(
                discrepancy(1L, DiscrepancyType.AMOUNT_MISMATCH, "10000", "-300", DiscrepancyStatus.APPROVED),
                discrepancy(2L, DiscrepancyType.AMOUNT_MISMATCH, "10000", "-700", DiscrepancyStatus.REJECTED),
                discrepancy(3L, DiscrepancyType.AMOUNT_MISMATCH, "10000", "-100", DiscrepancyStatus.PENDING)))));

        ClawbackImpact impact = service.previewRun(1L);

        assertThat(impact.clawbackCount()).isEqualTo(1);
        assertThat(impact.totalClawbackAmount()).isEqualByComparingTo("100");
    }

    @Test @DisplayName("행별로 어느 결제에서 얼마가 회수되는지 보여준다")
    void reportsPerLine() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(runWith(List.of(
                discrepancy(1L, DiscrepancyType.AMOUNT_MISMATCH, "10000", "-300", DiscrepancyStatus.PENDING)))));

        ClawbackImpact impact = service.previewRun(1L);

        assertThat(impact.lines()).hasSize(1);
        assertThat(impact.lines().get(0).paymentId()).isEqualTo(101L);
        assertThat(impact.lines().get(0).clawbackAmount()).isEqualByComparingTo("300");
    }

    @Test @DisplayName("차이가 없으면 0 건")
    void emptyRun() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(runWith(List.of())));

        ClawbackImpact impact = service.previewRun(1L);

        assertThat(impact.clawbackCount()).isZero();
        assertThat(impact.totalClawbackAmount()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("없는 run 은 타입 예외")
    void missingRun() {
        when(loadPort.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.previewRun(9L))
                .isInstanceOf(github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException.class);
    }
}
