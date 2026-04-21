package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;
    @InjectMocks RefundPaymentUseCase refundPaymentUseCase;

    private PaymentDomain capturedPayment() {
        return new PaymentDomain(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx-123", null, null, null);
    }

    @Test @DisplayName("정상 환불 처리")
    void refund_success() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("50000"));
        verify(updateOrderStatusPort).updateOrderStatus(10L, "REFUNDED");
        verify(publishEventPort).publishPaymentRefunded(any(), eq(10L));
        verify(adjustSettlementForRefundUseCase).adjustSettlementForRefund(any(), eq(new BigDecimal("50000")));
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void refund_paymentNotFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test @DisplayName("정산 조정 실패해도 환불은 성공")
    void refund_settlementAdjustFails_refundStillSucceeds() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("settlement error"))
                .when(adjustSettlementForRefundUseCase).adjustSettlementForRefund(any(), any());

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
}
