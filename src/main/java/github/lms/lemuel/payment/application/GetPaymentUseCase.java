package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.Payment;
import github.lms.lemuel.payment.port.in.GetPaymentPort;
import github.lms.lemuel.payment.port.out.LoadPaymentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GetPaymentUseCase implements GetPaymentPort {

    private final LoadPaymentPort loadPaymentPort;

    public GetPaymentUseCase(LoadPaymentPort loadPaymentPort) {
        this.loadPaymentPort = loadPaymentPort;
    }

    @Override
    public Payment getPayment(Long paymentId) {
        return loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
    }
}
