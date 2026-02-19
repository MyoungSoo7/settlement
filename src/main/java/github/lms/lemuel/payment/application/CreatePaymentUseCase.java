package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.domain.Payment;
import github.lms.lemuel.payment.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.port.out.LoadOrderPort;
import github.lms.lemuel.payment.port.out.SavePaymentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CreatePaymentUseCase implements CreatePaymentPort {

    private final LoadOrderPort loadOrderPort;
    private final SavePaymentPort savePaymentPort;

    public CreatePaymentUseCase(LoadOrderPort loadOrderPort, SavePaymentPort savePaymentPort) {
        this.loadOrderPort = loadOrderPort;
        this.savePaymentPort = savePaymentPort;
    }

    @Override
    public Payment createPayment(CreatePaymentCommand command) {
        // Load order information
        LoadOrderPort.OrderInfo order = loadOrderPort.loadOrder(command.getOrderId());
        
        if (!order.isCreated()) {
            throw new IllegalStateException("Order must be in CREATED status");
        }

        // Create payment domain entity
        Payment payment = new Payment(
            order.getId(),
            order.getAmount(),
            command.getPaymentMethod()
        );

        // Save and return
        return savePaymentPort.save(payment);
    }
}
