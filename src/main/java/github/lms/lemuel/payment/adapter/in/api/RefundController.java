package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.adapter.in.dto.PaymentResponse;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Refund REST API Controller
 *
 * POST /api/refunds/{paymentId}         - 환불 요청 (전액)
 * POST /api/refunds/full/{paymentId}    - 전액 환불
 * POST /api/refunds/partial/{paymentId} - 부분 환불
 */
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundPaymentPort refundPaymentPort;

    /**
     * 환불 요청
     * POST /api/refunds/{paymentId}
     */
    @PostMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> createRefund(
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(paymentId);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 전액 환불
     * POST /api/refunds/full/{paymentId}
     */
    @PostMapping("/full/{paymentId}")
    public ResponseEntity<PaymentResponse> processFullRefund(
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(paymentId);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    /**
     * 부분 환불
     * POST /api/refunds/partial/{paymentId}
     */
    @PostMapping("/partial/{paymentId}")
    public ResponseEntity<PaymentResponse> processPartialRefund(
            @PathVariable Long paymentId,
            @RequestParam java.math.BigDecimal refundAmount,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        // TODO: 부분 환불 로직 구현 시 refundAmount 활용
        PaymentDomain paymentDomain = refundPaymentPort.refundPayment(paymentId);
        return ResponseEntity.ok(new PaymentResponse(paymentDomain));
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key 헤더가 필요합니다.");
        }
    }
}
