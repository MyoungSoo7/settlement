package github.lms.lemuel.controller;

import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.dto.PaymentResponse;
import github.lms.lemuel.service.RefundService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * 시나리오 1: 전체 환불
     */
    @PostMapping("/full/{paymentId}")
    public ResponseEntity<PaymentResponse> processFullRefund(@PathVariable Long paymentId) {
        Payment refundedPayment = refundService.processFullRefund(paymentId);
        return ResponseEntity.ok(new PaymentResponse(refundedPayment));
    }

    /**
     * 시나리오 2: 부분 환불
     */
    @PostMapping("/partial/{paymentId}")
    public ResponseEntity<PaymentResponse> processPartialRefund(
            @PathVariable Long paymentId,
            @RequestParam BigDecimal refundAmount) {
        Payment refundPayment = refundService.processPartialRefund(paymentId, refundAmount);
        return ResponseEntity.ok(new PaymentResponse(refundPayment));
    }

    /**
     * 시나리오 3: 결제 실패 환불 (취소)
     */
    @PostMapping("/failed/{paymentId}")
    public ResponseEntity<PaymentResponse> processFailedPaymentRefund(@PathVariable Long paymentId) {
        Payment canceledPayment = refundService.processFailedPaymentRefund(paymentId);
        return ResponseEntity.ok(new PaymentResponse(canceledPayment));
    }
}
