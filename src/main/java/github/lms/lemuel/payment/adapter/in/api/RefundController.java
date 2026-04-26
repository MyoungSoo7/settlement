package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Refund REST API Controller
 *
 * POST /api/refunds/{paymentId}         - 환불 요청 (전액)
 * POST /api/refunds/full/{paymentId}    - 전액 환불
 * POST /api/refunds/partial/{paymentId} - 부분 환불
 *
 * NOTE: Task 2.4에서 RefundResponse DTO + 단일 엔드포인트로 정식 재작성 예정.
 *       현재는 Task 2.2/2.3 빌드 통과를 위한 임시 구현.
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
    public ResponseEntity<Long> createRefund(
            @PathVariable Long paymentId,
            @RequestParam(required = false) BigDecimal refundAmount,
            @RequestParam(required = false) String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Refund refund = refundPaymentPort.refund(
                new RefundCommand(paymentId, refundAmount, idempotencyKey, reason));
        return ResponseEntity.ok(refund.getId());
    }

    /**
     * 전액 환불
     * POST /api/refunds/full/{paymentId}
     */
    @PostMapping("/full/{paymentId}")
    public ResponseEntity<Long> processFullRefund(
            @PathVariable Long paymentId,
            @RequestParam BigDecimal refundAmount,
            @RequestParam(required = false) String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Refund refund = refundPaymentPort.refund(
                new RefundCommand(paymentId, refundAmount, idempotencyKey, reason));
        return ResponseEntity.ok(refund.getId());
    }

    /**
     * 부분 환불
     * POST /api/refunds/partial/{paymentId}
     */
    @PostMapping("/partial/{paymentId}")
    public ResponseEntity<Long> processPartialRefund(
            @PathVariable Long paymentId,
            @RequestParam BigDecimal refundAmount,
            @RequestParam(required = false) String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Refund refund = refundPaymentPort.refund(
                new RefundCommand(paymentId, refundAmount, idempotencyKey, reason));
        return ResponseEntity.ok(refund.getId());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key 헤더가 필요합니다.");
        }
    }
}
