package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdjustSettlementForRefundServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    @Mock EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @InjectMocks AdjustSettlementForRefundService service;

    private Settlement settlement() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        s.setId(100L);
        return s;
    }

    @Test @DisplayName("환불 반영 + 역정산 레코드 생성, refundId 전달")
    void adjusts_and_writes_adjustment() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Settlement result = service.adjustSettlementForRefund(1L, new BigDecimal("20000"), 777L);

        assertThat(result.getRefundedAmount()).isEqualTo(new BigDecimal("20000"));
        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        SettlementAdjustment adj = captor.getValue();
        assertThat(adj.getSettlementId()).isEqualTo(100L);
        assertThat(adj.getRefundId()).isEqualTo(777L);
        assertThat(adj.getAmount()).isEqualTo(new BigDecimal("-20000"));
    }

    @Test @DisplayName("정산이 존재하지 않으면 SettlementNotFoundException")
    void throwsWhenSettlementMissing() {
        when(loadSettlementPort.findByPaymentIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adjustSettlementForRefund(
                999L, new BigDecimal("10000"), 1L))
                .isInstanceOf(SettlementNotFoundException.class);
    }

    @Test @DisplayName("레거시 2-arg 오버로드도 default 로 작동 (refundId=null)")
    void legacyOverload() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("15000"));

        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        assertThat(captor.getValue().getRefundId()).isNull();
    }

    @Test @DisplayName("3-arg 호출은 원장 역분개 작업을 정확한 인자로 아웃박스에 적재")
    void enqueues_ledger_reverse_when_refundId_present() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("12000"), 555L);

        verify(enqueueLedgerTaskPort).enqueueReverse(
                eq(100L), eq(555L), eq(new BigDecimal("12000")), eq(LocalDate.now()));
    }

    @Test @DisplayName("2-arg 레거시 호출은 원장 역분개 작업을 적재하지 않음 (refundId 없음)")
    void does_not_enqueue_for_legacy_2arg_call() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentIdForUpdate(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("15000"));

        verify(enqueueLedgerTaskPort, never()).enqueueReverse(anyLong(), anyLong(), any(), any());
    }
}
