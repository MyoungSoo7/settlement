package github.lms.lemuel.payment.application;

import github.lms.lemuel.common.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.common.exception.RefundExceedsPaymentException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RefundPaymentUseCase 테스트.
 *
 * <p>MSA 분리 이후: 정산 조정은 settlement-service 가 PaymentRefunded Kafka 이벤트를
 * 컨슈밍해서 처리한다. 따라서 이 테스트는 publishPaymentRefunded 호출 검증으로 대체.</p>
 */
@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadRefundPort loadRefundPort;
    @Mock SaveRefundPort saveRefundPort;
    @InjectMocks RefundPaymentUseCase refundPaymentUseCase;

    private PaymentDomain capturedPayment() {
        return new PaymentDomain(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx-123", null, null, null);
    }

    private PaymentDomain partiallyRefundedPayment(BigDecimal alreadyRefunded) {
        return new PaymentDomain(1L, 10L, new BigDecimal("50000"), alreadyRefunded,
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

    @Test @DisplayName("전액 환불: amount=null 이면 결제 전액이 환불되고 Payment 는 REFUNDED")
    void fullRefund_defaultAmount() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRefundPersistence();

        PaymentDomain result = refundPaymentUseCase.refundPayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("50000"));
        verify(updateOrderStatusPort).updateOrderStatus(10L, "REFUNDED");
        verify(publishEventPort).publishPaymentRefunded(eq(1L), eq(10L));
    }

    @Test @DisplayName("부분 환불: amount < 결제금액이면 Payment 는 CAPTURED 유지, 주문 상태 미변경")
    void partialRefund_stillCaptured() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRefundPersistence();

        PaymentDomain result = refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "partial-key-1");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(result.getRefundedAmount()).isEqualTo(new BigDecimal("20000"));
        verify(pgClientPort).refund("pg-tx-123", new BigDecimal("20000"));
        verify(updateOrderStatusPort, never()).updateOrderStatus(any(), any());
        verify(publishEventPort).publishPaymentRefunded(eq(1L), eq(10L));
    }

    @Test @DisplayName("부분 환불로 전액 도달 시 Payment REFUNDED + 주문 상태 REFUNDED")
    void partialRefund_reachesFull_transitionsToRefunded() {
        PaymentDomain payment = partiallyRefundedPayment(new BigDecimal("30000"));
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRefundPersistence();

        PaymentDomain result = refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "partial-key-2");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(updateOrderStatusPort).updateOrderStatus(10L, "REFUNDED");
    }

    @Test @DisplayName("부분 환불에 idempotencyKey 가 없으면 MissingIdempotencyKeyException")
    void partialRefund_requiresIdempotencyKey() {
        PaymentDomain payment = capturedPayment();
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("10000"), null))
                .isInstanceOf(MissingIdempotencyKeyException.class);
        verify(pgClientPort, never()).refund(any(), any());
    }

    @Test @DisplayName("잔여 환불 가능 금액 초과 시 RefundExceedsPaymentException")
    void refund_exceedsRefundable() {
        PaymentDomain payment = partiallyRefundedPayment(new BigDecimal("40000"));
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(
                1L, new BigDecimal("20000"), "too-much"))
                .isInstanceOf(RefundExceedsPaymentException.class);
        verify(pgClientPort, never()).refund(any(), any());
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void refund_paymentNotFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test @DisplayName("이미 REFUNDED 면 PG 호출 없이 멱등 반환")
    void refund_alreadyRefunded_noPgCall() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("50000"), new BigDecimal("50000"),
                PaymentStatus.REFUNDED, "CARD", "pg-tx-123", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        refundPaymentUseCase.refundPayment(1L);

        verify(pgClientPort, never()).refund(any(), any());
        verify(saveRefundPort, never()).save(any());
    }

    @Test @DisplayName("동일 idempotencyKey 로 COMPLETED Refund 가 있으면 PG 재호출 없음")
    void refund_existingCompleted_noPgCall() {
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

    @Test @DisplayName("CAPTURED 가 아닌 상태에서는 환불 시도 시 예외 + PG 호출 없음")
    void refund_notCaptured_throwsBeforePgCall() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("50000"), BigDecimal.ZERO,
                PaymentStatus.READY, "CARD", null, null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> refundPaymentUseCase.refundPayment(1L))
                .isInstanceOf(IllegalStateException.class);
        verify(pgClientPort, never()).refund(any(), any());
    }
}
