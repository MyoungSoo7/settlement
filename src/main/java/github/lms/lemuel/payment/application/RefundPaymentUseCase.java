package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.Payment;
import github.lms.lemuel.payment.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.port.out.SavePaymentPort;
import github.lms.lemuel.payment.port.out.UpdateOrderStatusPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RefundPaymentUseCase implements RefundPaymentPort {

    private final LoadPaymentPort loadPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;

    public RefundPaymentUseCase(LoadPaymentPort loadPaymentPort,
                                SavePaymentPort savePaymentPort,
                                UpdateOrderStatusPort updateOrderStatusPort) {
        this.loadPaymentPort = loadPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
    }

    @Override
    public Payment refundPayment(Long paymentId) {
        Payment payment = loadPaymentPort.loadById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        // Domain logic
        payment.refund();

        Payment savedPayment = savePaymentPort.save(payment);

        // Update order status
        updateOrderStatusPort.updateOrderStatus(payment.getOrderId(), "REFUNDED");

        return savedPayment;
    }
}
