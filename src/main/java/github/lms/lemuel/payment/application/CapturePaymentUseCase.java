package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.Payment;
import github.lms.lemuel.payment.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.port.out.SavePaymentPort;
import github.lms.lemuel.payment.port.out.UpdateOrderStatusPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CapturePaymentUseCase implements CapturePaymentPort {

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;

    public CapturePaymentUseCase(LoadPaymentPort loadPaymentPort, 
                                 SavePaymentPort savePaymentPort,
                                 UpdateOrderStatusPort updateOrderStatusPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
    }

    @Override
    public Payment capturePayment(Long paymentId) {
        Payment payment = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        // Domain logic
        payment.capture();

        Payment savedPayment = savePaymentPort.save(payment);

        // Update order status
        updateOrderStatusPort.updateOrderStatus(payment.getOrderId(), "PAID");

        return savedPayment;
    }
}
