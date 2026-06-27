package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.MissingIdempotencyKeyException;
import github.lms.lemuel.payment.domain.exception.OrderNotFoundException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.domain.exception.RefundExceedsPaymentException;
import github.lms.lemuel.payment.domain.exception.RefundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionHandler {

    @ExceptionHandler({PaymentNotFoundException.class, OrderNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFoundException(RuntimeException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("message", ex.getMessage());
        errorResponse.put("status", HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    // InvalidPaymentStateException/InvalidOrderStateException 는 결제 도메인 고유 예외라 여기서 매핑.
    // 일반 IllegalStateException/IllegalArgumentException → 400 매핑은 GlobalExceptionHandler 로 일원화했다.
    @ExceptionHandler({InvalidPaymentStateException.class, InvalidOrderStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequestException(RuntimeException ex) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("message", ex.getMessage());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    // ─── 환불 도메인 예외 (shared-common GlobalExceptionHandler 에서 이관 — HTTP/errorCode 보존) ───

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleMissingIdempotencyKey(MissingIdempotencyKeyException ex) {
        return refundError(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", ex.getMessage());
    }

    @ExceptionHandler(RefundExceedsPaymentException.class)
    public ResponseEntity<Map<String, Object>> handleRefundExceedsPayment(RefundExceedsPaymentException ex) {
        return refundError(HttpStatus.CONFLICT, "REFUND_EXCEEDS_PAYMENT", ex.getMessage());
    }

    @ExceptionHandler(RefundException.class)
    public ResponseEntity<Map<String, Object>> handleRefundException(RefundException ex) {
        return refundError(HttpStatus.INTERNAL_SERVER_ERROR, "REFUND_ERROR", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> refundError(HttpStatus status, String errorCode, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", status.value());
        errorResponse.put("errorCode", errorCode);
        errorResponse.put("message", message);
        return ResponseEntity.status(status).body(errorResponse);
    }
}
