package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.recovery.application.port.in.RecordPostPayoutRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
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
    @Mock RecordPostPayoutRecoveryUseCase recordPostPayoutRecoveryUseCase;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    @Mock AuditLogger auditLogger;
    SimpleMeterRegistry meterRegistry;
    ApplyReconciliationAdjustmentService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ApplyReconciliationAdjustmentService(
                loadSettlementPort, saveSettlementPort, saveSettlementAdjustmentPort,
                enqueueLedgerTaskPort, recordPostPayoutRecoveryUseCase,
                loadSellerIdPort, publishSettlementDomainEventPort, meterRegistry,
                auditLogger, Clock.system(ZoneId.of("Asia/Seoul")));
        // DONE 분기가 저장된 조정의 id 를 채권 발생에 넘긴다 — echo 스텁으로 id 부여.
        lenient().when(saveSettlementAdjustmentPort.save(any(SettlementAdjustment.class)))
                .thenAnswer(inv -> {
                    SettlementAdjustment saved = mock(SettlementAdjustment.class);
                    lenient().when(saved.getId()).thenReturn(777L);
                    return saved;
                });
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
    @DisplayName("정상 적용 + 셀러 해석: 즉시분(SELLER_PAYABLE) 조정 이벤트를 발행한다(홀드백 0 → 소진 이벤트 없음)")
    void applied_publishesAdjusted_whenSellerPresent() {
        when(saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(55L)).thenReturn(false);
        Settlement s = settlementWithNet96500(SettlementStatus.REQUESTED);
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(55L));

        service.applyClawback(1L, 55L, new BigDecimal("1000"));

        // 홀드백 미적용(0) → 유보 소진 이벤트 없음, clawback 전액이 즉시분(SELLER_PAYABLE) 조정.
        verify(publishSettlementDomainEventPort).publishSettlementAdjusted(
                eq(777L), eq(500L), eq(55L), eq(new BigDecimal("1000")), eq("SELLER_PAYABLE"));
        verify(publishSettlementDomainEventPort, never())
                .publishHoldbackConsumed(anyLong(), any(), anyLong(), any());
        verify(publishSettlementDomainEventPort, never())
                .publishSettlementCanceled(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("HIGH-A(GL 재감사): 초과 clawback으로 net<=0(CANCELED) 시 SELLER_PAYABLE 조정은 priorImmediate 상한을 넘지 않는다")
    void caps_payableDelta_at_priorImmediate_on_clawback_exceeding_net() {
        // net=96500, holdback 30%=28950, priorImmediate=67550. clawback=100000(>net, 초과) →
        // holdback 전액(28950) 흡수 후 잔여(71050)가 즉시분 인식액(67550)을 넘는다. 상한 없이 발행하면
        // SELLER_PAYABLE=67550-71050=-3500(=수수료만큼 음수 잔존) — 수정 전엔 71050 이 발행되어 실패,
        // 수정 후엔 67550(=priorImmediate)으로 캡핑돼 통과한다.
        when(saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(55L)).thenReturn(false);
        Settlement s = settlementWithNet96500(SettlementStatus.REQUESTED);
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(55L));

        service.applyClawback(1L, 55L, new BigDecimal("100000"));

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        verify(publishSettlementDomainEventPort).publishHoldbackConsumed(
                eq(777L), eq(500L), eq(55L), eq(new BigDecimal("28950.00")));
        // 상한 캡핑 — priorImmediate(67550.00) 을 넘지 않는다 (버그 시엔 71050.00 이 발행됨).
        verify(publishSettlementDomainEventPort).publishSettlementAdjusted(
                eq(777L), eq(500L), eq(55L), eq(new BigDecimal("67550.00")), eq("SELLER_PAYABLE"));
        // CANCELED 분기 — 음수 잔여를 0 으로 클램프(계약 minimum 0 위반 방지). scale 이 다를 수 있어
        // eq() 대신 수치 비교(isEqualByComparingTo)로 검증한다.
        ArgumentCaptor<BigDecimal> immediateCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> holdbackCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(publishSettlementDomainEventPort).publishSettlementCanceled(
                eq(500L), eq(55L), immediateCaptor.capture(), holdbackCaptor.capture());
        assertThat(immediateCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(holdbackCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
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
        // 송금후 회수도 조정 ↔ 역분개 1:1(INV-5) — 역분개를 적재하고, 지급후 채권 발생은
        // RecordPostPayoutRecoveryUseCase 로 위임한다(저장된 조정 id 전달, seed-p0-6).
        verify(enqueueLedgerTaskPort).enqueueReverseReconciliation(
                eq(500L), eq(55L), eq(new BigDecimal("1000")), any());
        verify(recordPostPayoutRecoveryUseCase).recordIfPostPayout(
                eq(500L), eq(777L), eq(new BigDecimal("1000")), any());
        // DONE 분기의 회계 이벤트(채권 발생·유보 소진)는 recordIfPostPayout 이 소유 —
        // ApplyReconciliationAdjustmentService 는 이 경로에서 직접 발행하지 않는다(이중 발행 방지).
        verifyNoInteractions(publishSettlementDomainEventPort);
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
