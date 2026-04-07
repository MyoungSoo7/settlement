package github.lms.lemuel.common.exception;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ─── 4xx ────────────────────────────────────────────────────────────────────

    /**
     * 400 - @Valid @RequestBody 검증 실패
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("[MethodArgumentNotValidException] {}", message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    /**
     * 400 - @Validated PathVariable / QueryParam 검증 실패
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return field + ": " + v.getMessage();
                })
                .collect(Collectors.joining(", "));
        log.warn("[ConstraintViolationException] {}", message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    /**
     * 404 - 리소스 없음 (IllegalArgumentException)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[IllegalArgumentException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage());
    }

    /**
     * 409 - 비즈니스 규칙 위반 (IllegalStateException)
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[IllegalStateException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", ex.getMessage());
    }

    /**
     * 400 - Idempotency-Key 누락
     */
    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleMissingIdempotencyKey(MissingIdempotencyKeyException ex) {
        log.warn("[MissingIdempotencyKeyException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", ex.getMessage());
    }

    /**
     * 409 - 환불 금액 초과
     */
    @ExceptionHandler(RefundExceedsPaymentException.class)
    public ResponseEntity<Map<String, Object>> handleRefundExceedsPayment(RefundExceedsPaymentException ex) {
        log.warn("[RefundExceedsPaymentException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "REFUND_EXCEEDS_PAYMENT", ex.getMessage());
    }

    /**
     * 409 - 잘못된 결제 상태 전이
     */
    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPaymentState(InvalidPaymentStateException ex) {
        log.warn("[InvalidPaymentStateException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE", ex.getMessage());
    }

    // ─── 5xx ────────────────────────────────────────────────────────────────────

    /**
     * 500 - 환불 처리 오류
     */
    @ExceptionHandler(RefundException.class)
    public ResponseEntity<Map<String, Object>> handleRefundException(RefundException ex) {
        log.error("[RefundException] {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "REFUND_ERROR", ex.getMessage());
    }

    /**
     * 500 - 예상치 못한 시스템 예외 폴백
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("[Exception] 처리되지 않은 예외 발생", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
    }

    // ─── 공통 응답 빌더 ──────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "errorCode", errorCode,
                "message", message
        ));
    }
}
