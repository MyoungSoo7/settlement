package github.lms.lemuel.pgreconciliation.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRunStatus;
import github.lms.lemuel.pgreconciliation.domain.exception.InvalidReconciliationStateException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마감 유스케이스 — 도메인 규칙을 지키면서 감사 추적을 남긴다.
 */
@ExtendWith(MockitoExtension.class)
class CloseReconciliationRunServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 8, 5);

    @Mock LoadReconciliationRunPort loadPort;
    @Mock SaveReconciliationRunPort savePort;
    @Mock AuditLogger auditLogger;

    SimpleMeterRegistry meterRegistry;
    CloseReconciliationRunService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CloseReconciliationRunService(loadPort, savePort, auditLogger, meterRegistry);
    }

    private static ReconciliationRun completed(List<ReconciliationDiscrepancy> found) {
        ReconciliationRun run = ReconciliationRun.start("TOSS", D, "toss.csv", "op-1", "sha");
        run.complete(10, 10, 10 - found.size(), found);
        return run;
    }

    private static ReconciliationDiscrepancy pending() {
        return ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 1L, "TX-1",
                new BigDecimal("10000"), new BigDecimal("9000"));
    }

    @Test
    @DisplayName("정상 마감: CLOSED 로 전이하고 저장한다")
    void closesAndSaves() {
        ReconciliationRun run = completed(List.of());
        when(loadPort.findById(7L)).thenReturn(Optional.of(run));
        when(savePort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationRun result = service.close(7L, "op-2", "8월 1주차 마감");

        assertThat(result.getStatus()).isEqualTo(ReconciliationRunStatus.CLOSED);
        assertThat(result.getClosedBy()).isEqualTo("op-2");
        verify(savePort).saveAll(run);
    }

    @Test
    @DisplayName("마감은 audit_logs 에 기록된다 — 누가 언제 어느 기간을 잠갔는지 남는다")
    void recordsAudit() {
        ReconciliationRun run = completed(List.of());
        when(loadPort.findById(7L)).thenReturn(Optional.of(run));
        when(savePort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(7L, "op-2", "마감");

        verify(auditLogger).record(eq(AuditAction.PG_RECONCILIATION_CLOSED), eq("PgReconciliationRun"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("미결 불일치가 있으면 마감 거부 — 저장도 감사도 하지 않는다")
    void rejectsWithPendingAndDoesNotSave() {
        ReconciliationRun run = completed(List.of(pending()));
        when(loadPort.findById(7L)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.close(7L, "op-2", null))
                .isInstanceOf(InvalidReconciliationStateException.class)
                .hasMessageContaining("미결");

        verify(savePort, never()).saveAll(any());
        verify(auditLogger, never()).record(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 run 은 404 계열 예외")
    void rejectsUnknownRun() {
        when(loadPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(99L, "op-2", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(savePort, never()).saveAll(any());
    }

    @Test
    @DisplayName("마감 건수는 메트릭으로 관측된다")
    void incrementsMetric() {
        ReconciliationRun run = completed(List.of());
        when(loadPort.findById(7L)).thenReturn(Optional.of(run));
        when(savePort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(7L, "op-2", null);

        assertThat(meterRegistry.counter("pg.reconciliation.runs.closed", "provider", "TOSS").count())
                .isEqualTo(1.0);
    }
}
