package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadRefundPort loadRefundPort;
    @Mock SaveRefundPort saveRefundPort;
    @Mock AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;
    @InjectMocks RefundPaymentUseCase refundPaymentUseCase;

    private PaymentDomain capturedPayment() {
        return new PaymentDomain(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx-123", null, null, null);
    }

    private void stubRefundPersistence() {
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(saveRefundPort.save(any())).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.setId(999L);
            return r;
        });
    }

    @Test @DisplayName("정상 환불 처리 - Refund 엔티티 생성·완료 및 정산 조정 호출")
    void refund_success() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRefundPersistence();

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("50000"));
        verify(updateOrderStatusPort).updateOrderStatus(10L, "REFUNDED");
        verify(publishEventPort).publishPaymentRefunded(any(), eq(10L));
        verify(adjustSettlementForRefundUseCase).adjustSettlementForRefund(
                eq(1L), eq(new BigDecimal("50000")), eq(999L));
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void refund_paymentNotFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test @DisplayName("이미 REFUNDED 상태면 PG 호출 없이 멱등 반환")
    void refund_alreadyRefunded_noPgCall() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.REFUNDED, "CARD", "pg-tx-123", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(pgClientPort, never()).refund(any(), any());
        verify(saveRefundPort, never()).save(any());
    }

    @Test @DisplayName("기존 Refund 가 COMPLETED 면 PG 재호출 없이 멱등 반환")
    void refund_existingCompletedRefund_noPgCall() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        Refund existing = Refund.request(1L, new BigDecimal("50000"), "payment-1-full", "x");
        existing.setId(999L);
        existing.markCompleted();
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "payment-1-full"))
                .thenReturn(Optional.of(existing));

        refundPaymentUseCase.refundPayment(1L);

        verify(pgClientPort, never()).refund(any(), any());
        verify(saveRefundPort, never()).save(any());
    }

    @Test @DisplayName("CAPTURED 가 아닌 상태(READY)에서 환불 시도하면 예외 + PG 호출 없음")
    void refund_notCaptured_throwsBeforePgCall() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.READY, "CARD", null, null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(1L))
                .isInstanceOf(IllegalStateException.class);
        verify(pgClientPort, never()).refund(any(), any());
    }

    @Test @DisplayName("정산 조정 실패해도 환불은 성공")
    void refund_settlementAdjustFails_refundStillSucceeds() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRefundPersistence();
        doThrow(new RuntimeException("settlement error"))
                .when(adjustSettlementForRefundUseCase).adjustSettlementForRefund(any(), any(), any());

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
}
