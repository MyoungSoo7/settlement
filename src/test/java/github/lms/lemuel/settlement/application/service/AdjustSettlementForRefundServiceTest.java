package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjustSettlementForRefundService")
class AdjustSettlementForRefundServiceTest {

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;

    @InjectMocks
    private AdjustSettlementForRefundService service;

    private Settlement makeSettlement(Long id, BigDecimal amount) {
        Settlement s = Settlement.createFromPayment(1L, 10L, amount, LocalDate.of(2026, 1, 1));
        s.setId(id);
        return s;
    }

    @Nested
    @DisplayName("환불 조정 성공")
    class Success {

        @Test
        @DisplayName("환불 금액이 netAmount를 조정하고 저장한다")
        void adjust_partialRefund_savesAdjustedSettlement() {
            Settlement s = makeSettlement(5L, new BigDecimal("100000"));
            given(loadSettlementPort.findByPaymentId(1L)).willReturn(Optional.of(s));
            given(saveSettlementPort.save(any())).willAnswer(inv -> inv.getArgument(0));

            Settlement result = service.adjustSettlementForRefund(1L, new BigDecimal("10000"));

            assertThat(result.getRefundedAmount()).isEqualByComparingTo("10000");
            assertThat(result.getNetAmount()).isEqualByComparingTo("87000.00");
            then(saveSettlementPort).should().save(s);
        }

        @Test
        @DisplayName("전액 환불 시 CANCELED 상태로 저장된다")
        void adjust_fullRefund_savesCanceledSettlement() {
            Settlement s = makeSettlement(5L, new BigDecimal("100000"));
            given(loadSettlementPort.findByPaymentId(1L)).willReturn(Optional.of(s));
            given(saveSettlementPort.save(any())).willAnswer(inv -> inv.getArgument(0));

            Settlement result = service.adjustSettlementForRefund(1L, new BigDecimal("100000"));

            assertThat(result.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        }
    }

    @Nested
    @DisplayName("정산 미존재")
    class NotFound {

        @Test
        @DisplayName("paymentId에 해당하는 정산이 없으면 SettlementNotFoundException을 던진다")
        void adjust_settlementNotFound_throws() {
            given(loadSettlementPort.findByPaymentId(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.adjustSettlementForRefund(999L, new BigDecimal("1000")))
                .isInstanceOf(SettlementNotFoundException.class)
                .hasMessageContaining("999");

            then(saveSettlementPort).should(never()).save(any());
        }
    }
}