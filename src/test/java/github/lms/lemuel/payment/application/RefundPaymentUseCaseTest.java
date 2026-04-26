package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.Refund;
import github.lms.lemuel.payment.domain.RefundStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundPaymentUseCaseTest {

    @Mock private LoadPaymentPort loadPaymentPort;
    @Mock private SavePaymentPort savePaymentPort;
    @Mock private LoadRefundPort loadRefundPort;
    @Mock private SaveRefundPort saveRefundPort;
    @Mock private PgClientPort pgClientPort;
    @Mock private UpdateOrderStatusPort updateOrderStatusPort;
    @Mock private PublishEventPort publishEventPort;
    @Mock private AdjustSettlementForRefundUseCase adjustSettlementForRefundUseCase;

    @InjectMocks private RefundPaymentUseCase useCase;

    private PaymentDomain captured() {
        return new PaymentDomain(
                10L, 100L, new BigDecimal("100000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "PG-X",
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("부분환불: PG 호출, Refund INSERT, Payment 업데이트, 정산 조정 호출")
    void partial_refund_happy_path() {
        given(loadRefundPort.findByPaymentIdAndIdempotencyKey(10L, "K1"))
                .willReturn(Optional.empty());
        given(loadPaymentPort.loadById(10L)).willReturn(Optional.of(captured()));
        given(saveRefundPort.save(any(Refund.class))).willAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.assignId(99L);
            return r;
        });
        given(savePaymentPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        Refund result = useCase.refund(new RefundCommand(10L, new BigDecimal("30000"), "K1", "변심"));

        assertThat(result.getId()).isEqualTo(99L);
        assertThat(result.getAmount()).isEqualByComparingTo("30000");
        assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);

        verify(pgClientPort).refund("PG-X", new BigDecimal("30000"));
        verify(savePaymentPort).save(argThat(p -> p.getRefundedAmount().compareTo(new BigDecimal("30000")) == 0));
        verify(adjustSettlementForRefundUseCase)
                .adjustSettlementForRefund(eq(99L), eq(10L), eq(new BigDecimal("30000")));
    }

    @Test
    @DisplayName("멱등성: 동일 (paymentId, idempotencyKey)면 기존 Refund 반환, PG 재호출 없음")
    void idempotent_replay_returns_existing() {
        Refund existing = mock(Refund.class);
        given(loadRefundPort.findByPaymentIdAndIdempotencyKey(10L, "K1"))
                .willReturn(Optional.of(existing));

        Refund result = useCase.refund(new RefundCommand(10L, new BigDecimal("30000"), "K1", null));

        assertThat(result).isSameAs(existing);
        verify(pgClientPort, never()).refund(any(), any());
        verify(savePaymentPort, never()).save(any());
        verify(saveRefundPort, never()).save(any());
        verify(adjustSettlementForRefundUseCase, never())
                .adjustSettlementForRefund(any(), any(), any());
    }

    @Test
    @DisplayName("결제 없음: PaymentNotFoundException")
    void payment_not_found() {
        given(loadRefundPort.findByPaymentIdAndIdempotencyKey(10L, "K1"))
                .willReturn(Optional.empty());
        given(loadPaymentPort.loadById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.refund(new RefundCommand(10L, new BigDecimal("30000"), "K1", null)))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
