package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.domain.Money;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementStatus;
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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdjustSettlementForRefundServiceTest {

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    @Mock private RecordJournalEntryUseCase recordJournalEntryUseCase;

    @InjectMocks private AdjustSettlementForRefundService service;

    private Settlement existingSettlement(BigDecimal payAmount, BigDecimal commission) {
        return new Settlement(
                500L, 10L, 100L, payAmount, BigDecimal.ZERO, commission,
                payAmount.subtract(commission),
                SettlementStatus.DONE, LocalDate.of(2026, 4, 25),
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("부분환불 30%: SettlementAdjustment INSERT, 원 Settlement 변경 없음, Ledger 분개 호출")
    void partial_refund_30pct() {
        Settlement settlement = existingSettlement(new BigDecimal("100000"), new BigDecimal("3000"));
        settlement.setSellerId(42L);
        given(loadSettlementPort.findByPaymentId(10L)).willReturn(Optional.of(settlement));
        given(saveSettlementAdjustmentPort.save(any())).willAnswer(inv -> {
            SettlementAdjustment adj = inv.getArgument(0);
            adj.assignId(7L);
            return adj;
        });

        service.adjustSettlementForRefund(99L, 10L, new BigDecimal("30000"));

        // (1) Adjustment INSERT 검증
        ArgumentCaptor<SettlementAdjustment> adjCap = ArgumentCaptor.forClass(SettlementAdjustment.class);
        verify(saveSettlementAdjustmentPort).save(adjCap.capture());
        SettlementAdjustment adj = adjCap.getValue();
        assertThat(adj.getRefundId()).isEqualTo(99L);
        assertThat(adj.getSettlementId()).isEqualTo(500L);
        assertThat(adj.getAmount()).isEqualByComparingTo("30000");

        // (2) 원 Settlement 변경 없음 (refundedAmount = 0 그대로)
        assertThat(settlement.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.DONE);

        // (3) Ledger 분개 호출 — commissionReversal = 3000 * (30000/100000) = 900
        verify(recordJournalEntryUseCase).recordRefundProcessed(
                eq(99L), eq(42L),
                eq(Money.krw(new BigDecimal("30000"))),
                eq(Money.krw(new BigDecimal("900"))));
    }

    @Test
    @DisplayName("Settlement 없음: SettlementNotFoundException")
    void settlement_not_found() {
        given(loadSettlementPort.findByPaymentId(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.adjustSettlementForRefund(99L, 10L, new BigDecimal("30000")))
                .isInstanceOf(SettlementNotFoundException.class);

        verifyNoInteractions(saveSettlementAdjustmentPort, recordJournalEntryUseCase);
    }

    @Test
    @DisplayName("정확한 비율 계산: 1/3 환불 시 단수처리(HALF_UP)")
    void rounding_check() {
        Settlement settlement = existingSettlement(new BigDecimal("100000"), new BigDecimal("3333"));
        settlement.setSellerId(42L);
        given(loadSettlementPort.findByPaymentId(10L)).willReturn(Optional.of(settlement));
        given(saveSettlementAdjustmentPort.save(any())).willAnswer(inv -> {
            SettlementAdjustment a = inv.getArgument(0); a.assignId(1L); return a;
        });

        service.adjustSettlementForRefund(99L, 10L, new BigDecimal("33333"));

        // 3333 * 33333 / 100000 = 111098889 / 100000 = 1110.98889 → 1110.99 (HALF_UP, scale 2)
        verify(recordJournalEntryUseCase).recordRefundProcessed(
                eq(99L), eq(42L),
                eq(Money.krw(new BigDecimal("33333"))),
                eq(Money.krw(new BigDecimal("1110.99"))));
    }
}
