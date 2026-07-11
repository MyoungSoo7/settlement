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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전 서비스 공통 전역 예외 처리기 — 모든 에러 응답을 {@link ErrorResponse} 단일 스키마로 통일한다.
 *
 * <p>도메인 비즈니스 예외는 {@link BusinessException}({@link ErrorCode} 보유) 하나로 수렴되어
 * {@link #handleBusiness} 단일 핸들러가 코드→HTTP 상태/응답으로 변환한다. 따라서 도메인별
 * {@code @RestControllerAdvice} 가 더 이상 필요 없다(과거 Order/User/Settlement/Payment/Product
 * advice 는 이 통합으로 제거됨). 새 도메인 예외는 {@code BusinessException} 을 상속하고 {@code ErrorCode}
 * 만 추가하면 자동으로 이 핸들러가 처리한다.
 */
@Slf4j
@Hidden
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    // ─── 도메인 비즈니스 예외 (단일 진입점) ──────────────────────────────────────

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        // 5xx 는 원인 추적이 필요하므로 stacktrace 까지, 4xx 는 메시지만 남긴다
        if (code.status().is5xxServerError()) {
            log.error("[BusinessException] {} - {}", code.code(), ex.getMessage(), ex);
        } else {
            log.warn("[BusinessException] {} - {}", code.code(), ex.getMessage());
        }
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), ex.getMessage(), ex.getDetails()));
    }

    // ─── 4xx 기술 예외 ──────────────────────────────────────────────────────────

    /**
     * 400 - @Valid @RequestBody 검증 실패. 필드별 오류 맵({@code field -> message})도 함께 노출.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : fieldErrors) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("[MethodArgumentNotValidException] {}", message);
        return badRequest(ErrorResponse.validation(
                HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST.code(), message, errors));
    }

    /**
     * 400 - @Validated PathVariable / QueryParam 검증 실패
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return field + ": " + v.getMessage();
                })
                .collect(Collectors.joining(", "));
        log.warn("[ConstraintViolationException] {}", message);
        return badRequest(ErrorResponse.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PARAMETER.code(), message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        String message = ex.getParameterName() + " parameter is required";
        log.warn("[MissingServletRequestParameterException] {}", message);
        return badRequest(ErrorResponse.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PARAMETER.code(), message));
    }

    /**
     * 400 - 잘못된 인자 (도메인 입력값 검증 실패).
     *
     * <p>과거 도메인별 ExceptionHandler 에 복제돼 있던 {@code IllegalArgumentException → 400} 매핑을
     * 이 공통 폴백으로 일원화한다. 전용 처리가 없는 서비스(loan-service 등)에서 이 예외가
     * {@link #handleException}(500) 으로 누수되던 문제도 함께 차단한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[IllegalArgumentException] {}", ex.getMessage());
        return badRequest(ErrorResponse.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT.code(), ex.getMessage()));
    }

    /**
     * 400 - 잘못된 상태에서의 요청 (도메인 불변식 위반).
     *
     * <p>상태별로 다른 HTTP 코드가 필요한 경우(예: 409/403)는 해당 컨트롤러/도메인이 전용 예외나
     * 로컬 처리로 직접 매핑하므로 이 공통 폴백에 도달하지 않는다.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.warn("[IllegalStateException] {}", ex.getMessage());
        return badRequest(ErrorResponse.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_STATE.code(), ex.getMessage()));
    }

    // ─── 5xx ────────────────────────────────────────────────────────────────────

    /**
     * 500 - 예상치 못한 시스템 예외 폴백.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("[Exception] 처리되지 않은 예외 발생", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.code(),
                        ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> badRequest(ErrorResponse body) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
