package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
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
    @InjectMocks CapturePaymentUseCase capturePaymentUseCase;

    @Test @DisplayName("AUTHORIZED → CAPTURED 후 PaymentCaptured 이벤트가 outbox 로 기록된다")
    void capture_success_publishesOutboxEvent() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.AUTHORIZED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDomain result = capturePaymentUseCase.capturePayment(1L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(publishEventPort).publishPaymentCaptured(1L, 10L, new BigDecimal("30000"));
        // 정산 생성은 CapturePaymentUseCase 가 직접 호출하지 않는다 —
        // Kafka 컨슈머가 이벤트 수신 후 수행하므로 여기서는 검증하지 않는다.
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void capture_notFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> capturePaymentUseCase.capturePayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
