package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.Payment;
import github.lms.lemuel.payment.port.in.AuthorizePaymentPort;
import github.lms.lemuel.payment.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.port.out.SavePaymentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class AuthorizePaymentUseCase implements AuthorizePaymentPort {

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;

    public AuthorizePaymentUseCase(LoadPaymentPort loadPaymentPort, SavePaymentPort savePaymentPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
    }

    @Override
    public Payment authorizePayment(Long paymentId) {
        Payment payment = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        // Simulate PG authorization
        String pgTransactionId = "PG-" + UUID.randomUUID().toString();
        
        // Domain logic
        payment.authorize(pgTransactionId);

        return savePaymentPort.save(payment);
    }
}
