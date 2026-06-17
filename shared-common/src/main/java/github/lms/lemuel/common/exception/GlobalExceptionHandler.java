package github.lms.lemuel.common.exception;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Hidden
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE) // 도메인별 ExceptionHandler 가 먼저 매칭되도록 폴백 우선순위를 최하위로 내린다
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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        String message = ex.getParameterName() + " parameter is required";
        log.warn("[MissingServletRequestParameterException] {}", message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    // ─── 5xx ────────────────────────────────────────────────────────────────────

    /**
     * 500 - 예상치 못한 시스템 예외 폴백
     *
     * <p>참고: 결제/환불 도메인 예외(RefundException 등)는 order-service 의
     * {@code PaymentExceptionHandler} 가 전담한다 (이 공통 핸들러는 순수 기술 폴백만 담당).
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
