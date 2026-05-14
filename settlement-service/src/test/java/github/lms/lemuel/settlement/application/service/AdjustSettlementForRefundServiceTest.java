package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.adapter.in.event.dto.LedgerReverseEntryEvent;
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
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdjustSettlementForRefundServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks AdjustSettlementForRefundService service;

    private Settlement settlement() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        s.setId(100L);
        return s;
    }

    @Test @DisplayName("환불 반영 + 역정산 레코드 생성, refundId 전달")
    void adjusts_and_writes_adjustment() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(s));
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
        when(loadSettlementPort.findByPaymentId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adjustSettlementForRefund(
                999L, new BigDecimal("10000"), 1L))
                .isInstanceOf(SettlementNotFoundException.class);
    }

    @Test @DisplayName("레거시 2-arg 오버로드도 default 로 작동 (refundId=null)")
    void legacyOverload() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("15000"));

        ArgumentCaptor<SettlementAdjustment> captor = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(captor.capture());
        assertThat(captor.getValue().getRefundId()).isNull();
    }

    @Test @DisplayName("3-arg 호출은 LedgerReverseEntryEvent 를 정확한 payload 로 발행")
    void publishes_ledger_reverse_event_when_refundId_present() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("12000"), 555L);

        ArgumentCaptor<LedgerReverseEntryEvent> captor = ArgumentCaptor.forClass(LedgerReverseEntryEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        LedgerReverseEntryEvent event = captor.getValue();
        assertThat(event.settlementId()).isEqualTo(100L);
        assertThat(event.refundId()).isEqualTo(555L);
        assertThat(event.refundAmount()).isEqualByComparingTo("12000");
        assertThat(event.adjustmentDate()).isEqualTo(LocalDate.now());
    }

    @Test @DisplayName("2-arg 레거시 호출은 LedgerReverseEntryEvent 발행하지 않음 (refundId 없음)")
    void does_not_publish_event_for_legacy_2arg_call() {
        Settlement s = settlement();
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(s));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(saveSettlementAdjustmentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSettlementForRefund(1L, new BigDecimal("15000"));

        verify(eventPublisher, never()).publishEvent(any(LedgerReverseEntryEvent.class));
    }
}
