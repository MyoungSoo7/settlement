package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
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
class CapturePaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock PgClientPort pgClientPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock CreateSettlementFromPaymentUseCase createSettlementFromPaymentUseCase;
    @InjectMocks CapturePaymentUseCase capturePaymentUseCase;

    @Test @DisplayName("AUTHORIZED → CAPTURED 성공")
    void capture_success() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDomain result = capturePaymentUseCase.capturePayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(createSettlementFromPaymentUseCase).createSettlementFromPayment(anyLong(), anyLong(), any());
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void capture_notFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> capturePaymentUseCase.capturePayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
