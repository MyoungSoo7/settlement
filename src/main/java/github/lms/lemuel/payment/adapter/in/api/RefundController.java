package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.adapter.in.dto.RefundResponse;
import github.lms.lemuel.payment.application.port.in.RefundCommand;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundPaymentPort refundPaymentPort;

    /**
     * 부분 환불 (또는 전체 환불 with refundAmount = paymentAmount).
     * POST /api/refunds/{paymentId}?refundAmount=...
     */
    @PostMapping("/{paymentId}")
    public ResponseEntity<RefundResponse> createRefund(
            @PathVariable Long paymentId,
            @RequestParam BigDecimal refundAmount,
            @RequestParam(required = false) String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Refund refund = refundPaymentPort.refund(
                new RefundCommand(paymentId, refundAmount, idempotencyKey, reason));
        return ResponseEntity.ok(RefundResponse.from(refund));
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key 헤더가 필요합니다.");
        }
    }
}
