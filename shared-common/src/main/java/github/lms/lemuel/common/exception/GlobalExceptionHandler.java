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
import java.util.LinkedHashMap;
import java.util.List;
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
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        // 도메인 핸들러(과거 Order/User)가 제공하던 필드별 검증 맵({field: message})을 보존해 일원화
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : fieldErrors) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("[MethodArgumentNotValidException] {}", message);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, errors);
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

    /**
     * 400 - 잘못된 인자 (도메인 입력값 검증 실패)
     *
     * <p>과거 Order/User/Settlement/Payment/Product 등 도메인별 ExceptionHandler 에 동일하게
     * 복제돼 있던 {@code IllegalArgumentException → 400} 매핑을 이 공통 폴백으로 일원화한다.
     * 전용 advice 가 없는 서비스(loan-service 등)에서 이 예외가 아래 {@link #handleException}
     * (500) 으로 누수되던 문제도 함께 차단한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[IllegalArgumentException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    /**
     * 400 - 잘못된 상태에서의 요청 (도메인 불변식 위반)
     *
     * <p>상태별로 다른 HTTP 코드가 필요한 경우(예: 409/403)는 해당 컨트롤러/도메인이 전용 예외나
     * 로컬 처리로 직접 매핑하므로 이 공통 폴백에 도달하지 않는다.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[IllegalStateException] {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_STATE", ex.getMessage());
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
        return buildErrorResponse(status, errorCode, message, null);
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String errorCode, String message, Map<String, String> fieldErrors) {
        // LinkedHashMap 사용: 키 순서 보존 + message 가 null 이어도 안전(Map.of 는 null 값 금지)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("errorCode", errorCode);
        body.put("message", message);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("errors", fieldErrors);
        }
        return ResponseEntity.status(status).body(body);
    }
}
