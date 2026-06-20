package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Settlement 모듈 전용 Exception Handler — GlobalExceptionHandler 보다 먼저 매칭되도록 우선순위 명시
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SettlementExceptionHandler {

    @ExceptionHandler(SettlementNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSettlementNotFoundException(SettlementNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePaymentNotFoundException(PaymentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalStateException(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
}
