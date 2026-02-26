package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.adapter.in.dto.PaymentRequest;
import github.lms.lemuel.payment.adapter.in.dto.PaymentResponse;
import github.lms.lemuel.payment.adapter.in.dto.TossCartConfirmRequest;
import github.lms.lemuel.payment.adapter.in.dto.TossPaymentConfirmRequest;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.*;
import github.lms.lemuel.payment.domain.PaymentDomain;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST Controller for Payment API - Maps HTTP requests to use case ports
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final CreatePaymentPort createPaymentPort;
    private final AuthorizePaymentPort authorizePaymentPort;
    private final CapturePaymentPort capturePaymentPort;
    private final RefundPaymentPort refundPaymentPort;
    private final GetPaymentPort getPaymentPort;
    private final TossPaymentService tossPaymentService;

    public PaymentController(CreatePaymentPort createPaymentPort,
                             AuthorizePaymentPort authorizePaymentPort,
                             CapturePaymentPort capturePaymentPort,
                             RefundPaymentPort refundPaymentPort,
                             GetPaymentPort getPaymentPort,
                             TossPaymentService tossPaymentService) {
        this.createPaymentPort = createPaymentPort;
        this.authorizePaymentPort = authorizePaymentPort;
        this.capturePaymentPort = capturePaymentPort;
        this.refundPaymentPort = refundPaymentPort;
        this.getPaymentPort = getPaymentPort;
        this.tossPaymentService = tossPaymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        CreatePaymentCommand command = new CreatePaymentCommand(
            request.getOrderId(),
            request.getPaymentMethod()
        );
        
        PaymentDomain paymentDomain = createPaymentPort.createPayment(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentResponse(paymentDomain));
    }

    @PatchMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorizePayment(@PathVariable Long id) {
        PaymentDomain paymentDomain = authorizePaymentPort.authorizePayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @PatchMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(@PathVariable Long id) {
        PaymentDomain paymentDomain = capturePaymentPort.capturePayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @PatchMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long id) {
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
        PaymentDomain paymentDomain = getPaymentPort.getPayment(id);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 토스페이먼츠 결제 확인
     * POST /payments/toss/confirm
     */
    @PostMapping("/toss/confirm")
    public ResponseEntity<PaymentResponse> confirmTossPayment(@Valid @RequestBody TossPaymentConfirmRequest request) {
        PaymentDomain paymentDomain = tossPaymentService.confirmTossPayment(
                request.getDbOrderId(),
                request.getPaymentKey(),
                request.getTossOrderId(),
                request.getAmount()
        );
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 토스페이먼츠 장바구니 일괄 결제 확인
     * POST /payments/toss/cart/confirm
     */
    @PostMapping("/toss/cart/confirm")
    public ResponseEntity<List<PaymentResponse>> confirmTossCartPayment(
            @Valid @RequestBody TossCartConfirmRequest request) {
        List<PaymentDomain> payments = tossPaymentService.confirmTossCartPayment(
                request.getOrderIds(),
                request.getPaymentKey(),
                request.getTossOrderId(),
                request.getTotalAmount()
        );
        List<PaymentResponse> responses = payments.stream()
                .map(PaymentResponse::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }
}
