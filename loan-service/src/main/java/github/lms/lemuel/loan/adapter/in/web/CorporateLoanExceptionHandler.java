package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.loan.domain.CorporateLoanNotFoundException;
import github.lms.lemuel.loan.domain.CorporateLoanRejectedException;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 기업 신용대출 전용 예외 매핑 — shared-common {@code GlobalExceptionHandler} 패턴을 확장한다.
 *
 * <ul>
 *   <li>{@link CorporateLoanRejectedException} → 422 Unprocessable Entity (신용 거절·한도초과·재무없음)</li>
 *   <li>{@link CorporateLoanNotFoundException} → 404 Not Found (대출/재무자료 없음)</li>
 * </ul>
 *
 * <p>공통 핸들러는 이 두 예외 타입을 처리하지 않으므로 충돌이 없으나, 명시적으로 최우선 순위를 부여해
 * 매핑을 고정한다. 그 외 {@code IllegalArgumentException}/{@code IllegalStateException} 등은 여전히
 * 공통 {@code GlobalExceptionHandler}(400)로 흐른다.
 */
@Hidden
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorporateLoanExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CorporateLoanExceptionHandler.class);

    @ExceptionHandler(CorporateLoanRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRejected(CorporateLoanRejectedException ex) {
        log.warn("[CorporateLoanRejected] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of(HttpStatus.UNPROCESSABLE_ENTITY,
                        "CORPORATE_LOAN_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(CorporateLoanNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CorporateLoanNotFoundException ex) {
        log.warn("[CorporateLoanNotFound] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND,
                        "CORPORATE_LOAN_NOT_FOUND", ex.getMessage()));
    }
}
