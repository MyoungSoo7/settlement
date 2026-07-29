package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import github.lms.lemuel.loan.domain.exception.SecuredLoanRejectedException;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 담보/개인신용 대출 전용 예외 매핑 — {@link CorporateLoanExceptionHandler} 와 같은 패턴.
 *
 * <ul>
 *   <li>{@link SecuredLoanRejectedException} → 422 (한도 초과·신용등급 미달)</li>
 *   <li>{@link SecuredLoanNotFoundException} → 404</li>
 * </ul>
 *
 * <p>상태 전이 위반({@code InvalidLoanStateException})은 {@code LoanDomainException} 계열이라 공통
 * {@code GlobalExceptionHandler}(400)로 흐른다 — 기존 두 대출과 동일한 웹 계약이다.
 */
@Hidden
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecuredLoanExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SecuredLoanExceptionHandler.class);

    @ExceptionHandler(SecuredLoanRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRejected(SecuredLoanRejectedException ex) {
        log.warn("[SecuredLoanRejected] {}", ex.getMessage());
        ErrorCode code = ErrorCode.SECURED_LOAN_REJECTED;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), ex.getMessage()));
    }

    @ExceptionHandler(SecuredLoanNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SecuredLoanNotFoundException ex) {
        log.warn("[SecuredLoanNotFound] {}", ex.getMessage());
        ErrorCode code = ErrorCode.SECURED_LOAN_NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), ex.getMessage()));
    }
}
