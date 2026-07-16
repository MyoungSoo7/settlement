package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 소유권/인증 위반(IDOR 방지) → 403 매핑. LoanController·CorporateLoanController 가 셀러 소유권을
 * JWT 주체에서 파생·대조하다 불일치/미인증이면 {@link AccessDeniedException} 을 던지며, 이 어드바이스가
 * 이를 403 Forbidden 으로 확정한다.
 *
 * <p>shared-common {@code GlobalExceptionHandler}(LOWEST_PRECEDENCE)는 이 예외 타입을 다루지 않아
 * 미매핑 시 500 으로 누수되므로, 최우선 순위로 잡아 매핑을 고정한다.
 */
@Hidden
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoanSecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoanSecurityExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("[AccessDenied] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage()));
    }
}
