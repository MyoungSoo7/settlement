package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
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
class GetPaymentUseCaseTest {

    @Mock LoadPaymentPort loadPaymentPort;
    @InjectMocks GetPaymentUseCase getPaymentUseCase;

    @Test @DisplayName("결제 조회 성공")
    void getPayment_success() {
        PaymentDomain payment = new PaymentDomain(1L, 10L, new BigDecimal("30000"), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "CARD", "pg-tx", null, null, null);
        when(loadPaymentPort.loadById(1L)).thenReturn(Optional.of(payment));

        PaymentDomain result = getPaymentUseCase.getPayment(1L);
        assertThat(result.getAmount()).isEqualByComparingTo("30000");
    }

    @Test @DisplayName("결제 미존재 시 예외")
    void getPayment_notFound() {
        when(loadPaymentPort.loadById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> getPaymentUseCase.getPayment(999L))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
