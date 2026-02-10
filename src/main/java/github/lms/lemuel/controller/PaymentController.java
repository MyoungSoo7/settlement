package github.lms.lemuel.controller;

import github.lms.lemuel.domain.Order;
import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.dto.PaymentRequest;
import github.lms.lemuel.dto.PaymentResponse;
import github.lms.lemuel.repository.OrderRepository;
import github.lms.lemuel.repository.PaymentRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public PaymentController(PaymentRepository paymentRepository, OrderRepository orderRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (order.getStatus() != Order.OrderStatus.CREATED) {
            throw new RuntimeException("Order must be in CREATED status");
        }

        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setAmount(order.getAmount());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setStatus(Payment.PaymentStatus.READY);

        Payment savedPayment = paymentRepository.save(payment);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentResponse(savedPayment));
    }

    @PatchMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorizePayment(@PathVariable Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        if (payment.getStatus() != Payment.PaymentStatus.READY) {
            throw new RuntimeException("Payment must be in READY status");
        }

        payment.setStatus(Payment.PaymentStatus.AUTHORIZED);
        payment.setPgTransactionId("PG-" + UUID.randomUUID().toString());

        Payment updatedPayment = paymentRepository.save(payment);
        return ResponseEntity.ok(new PaymentResponse(updatedPayment));
    }

    @PatchMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(@PathVariable Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        if (payment.getStatus() != Payment.PaymentStatus.AUTHORIZED) {
            throw new RuntimeException("Payment must be in AUTHORIZED status");
        }

        payment.setStatus(Payment.PaymentStatus.CAPTURED);
        Payment updatedPayment = paymentRepository.save(payment);

        // 주문 상태를 PAID로 변경
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(Order.OrderStatus.PAID);
        orderRepository.save(order);

        return ResponseEntity.ok(new PaymentResponse(updatedPayment));
    }

    @PatchMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        if (payment.getStatus() != Payment.PaymentStatus.CAPTURED) {
            throw new RuntimeException("Payment must be in CAPTURED status");
        }

        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        Payment updatedPayment = paymentRepository.save(payment);

        // 주문 상태를 REFUNDED로 변경
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(Order.OrderStatus.REFUNDED);
        orderRepository.save(order);

        return ResponseEntity.ok(new PaymentResponse(updatedPayment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        return ResponseEntity.ok(new PaymentResponse(payment));
    }
}
