package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ApplyReconciliationAdjustmentService} 적용 역학 검증 (타입별 clawback 판정은 컨슈머 테스트가 담당).
 * 커버: 정상 적용 / DONE 정산 감사-only / 재전송(기존 조정 존재) 무회수 / 정산 미존재 fail-loud.
 */
@ExtendWith(MockitoExtension.class)
class ApplyReconciliationAdjustmentServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    @Mock EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @Mock AuditLogger auditLogger;
    SimpleMeterRegistry meterRegistry;
    ApplyReconciliationAdjustmentService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ApplyReconciliationAdjustmentService(
                loadSettlementPort, saveSettlementPort, saveSettlementAdjustmentPort,
                enqueueLedgerTaskPort, meterRegistry,
                auditLogger, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /** 결제 100,000 / 3.5% → net 96,500, id 설정. */
    private Settlement settlementWithNet96500(SettlementStatus status) {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.assignId(500L);
        if (status == SettlementStatus.DONE) {
            s.confirm();
        }
        return s;
    }

    @Test
    @DisplayName("정상 적용: net 축소 + 감사 음수 레코드 + applied 메트릭")
    void applied() {
        when(saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(55L)).thenReturn(false);
        Settlement s = settlementWithNet96500(SettlementStatus.REQUESTED);
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.applyClawback(1L, 55L, new BigDecimal("1000"));

        assertThat(s.getNetAmount()).isEqualByComparingTo("95500.00");
        verify(saveSettlementPort).save(s);

        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        assertThat(captor.getValue().getReconciliationDiscrepancyId()).isEqualTo(55L);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("-1000");

        assertThat(meterRegistry.counter("pg.reconciliation.adjustments.applied").count()).isEqualTo(1.0);
        verify(auditLogger).record(eq(AuditAction.RECON_ADJUSTMENT_APPLIED), eq("Settlement"),
                eq("500"), contains("\"outcome\":\"APPLIED\""));

        // 조정과 함께 PG_RECONCILIATION 출처 원장 역분개가 같은 트랜잭션 Outbox 에 적재된다(discrepancyId 참조).
        ArgumentCaptor<BigDecimal> amt = ArgumentCaptor.forClass(BigDecimal.class);
        verify(enqueueLedgerTaskPort).enqueueReverseReconciliation(
                eq(500L), eq(55L), amt.capture(), any(LocalDate.class));
        assertThat(amt.getValue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("DONE 정산: 정산 저장 없이 감사 레코드만 + skipped{settlement_done_manual_clawback}")
    void doneSettlement_auditOnly() {
        when(saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(55L)).thenReturn(false);
        Settlement done = settlementWithNet96500(SettlementStatus.DONE);
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(done));

        service.applyClawback(1L, 55L, new BigDecimal("1000"));

        // 지급 완료 정산은 net 변경 금지 → 저장 안 함
        verify(saveSettlementPort, never()).save(any());
        // 감사 레코드는 남긴다
        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        assertThat(captor.getValue().getReconciliationDiscrepancyId()).isEqualTo(55L);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("-1000");

        assertThat(meterRegistry.counter("pg.reconciliation.adjustments.skipped",
                "reason", "settlement_done_manual_clawback").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("pg.reconciliation.adjustments.applied").count()).isEqualTo(0.0);
        // DONE 정산도 갭 추적을 위해 감사 레코드는 남긴다(outcome=DONE_MANUAL_CLAWBACK).
        verify(auditLogger).record(eq(AuditAction.RECON_ADJUSTMENT_APPLIED), eq("Settlement"),
                eq("500"), contains("\"outcome\":\"DONE_MANUAL_CLAWBACK\""));
        // 지급 완료(DONE) 정산의 회수는 송금후 회수 영역 — 원장 역분개를 적재하지 않는다(scope 밖).
        verify(enqueueLedgerTaskPort, never()).enqueueReverseReconciliation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("재전송(기존 조정 존재): 이중 회수 없이 즉시 반환")
    void alreadyApplied_noDoubleClawback() {
        when(saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(55L)).thenReturn(true);

        service.applyClawback(1L, 55L, new BigDecimal("1000"));

        verify(loadSettlementPort, never()).findByPaymentIdForUpdate(any());
        verify(saveSettlementPort, never()).save(any());
        verify(saveSettlementAdjustmentPort, never()).save(any());
        assertThat(meterRegistry.counter("pg.reconciliation.adjustments.skipped",
                "reason", "already_applied").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("정산 미존재: fail-loud (SettlementNotFoundException 전파 → DLT)")
    void settlementNotFound_failLoud() {
        when(saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(55L)).thenReturn(false);
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyClawback(1L, 55L, new BigDecimal("1000")))
                .isInstanceOf(SettlementNotFoundException.class);
        verify(saveSettlementPort, never()).save(any());
        verify(saveSettlementAdjustmentPort, never()).save(any());
    }
}
