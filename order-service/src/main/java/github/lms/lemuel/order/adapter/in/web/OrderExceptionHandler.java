package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Order 모듈 전용 Exception Handler — GlobalExceptionHandler 보다 먼저 매칭되도록 우선순위 명시.
 *
 * <p>공통 기술 예외(IllegalArgumentException/IllegalStateException/MethodArgumentNotValidException)는
 * shared-common 의 {@code GlobalExceptionHandler} 로 일원화했다. 여기서는 Order 도메인 고유 예외만 매핑한다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleOrderNotFoundException(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(UserNotExistsException.class)
    public ResponseEntity<Map<String, String>> handleUserNotExistsException(UserNotExistsException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
}
