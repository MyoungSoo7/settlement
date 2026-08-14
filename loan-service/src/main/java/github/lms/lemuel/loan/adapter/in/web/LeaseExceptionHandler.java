package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.loan.domain.exception.LeaseContractNotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 리스·할부 전용 예외 매핑 — {@link SecuredLoanExceptionHandler} 와 같은 패턴.
 *
 * <p>상태 전이·불변식 위반({@code LoanInvariantViolationException})은 {@code LoanDomainException} 계열이라
 * 공통 {@code GlobalExceptionHandler}(400)로 흐른다.
 */
@Hidden
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LeaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LeaseExceptionHandler.class);

    @ExceptionHandler(LeaseContractNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(LeaseContractNotFoundException ex) {
        log.warn("[LeaseContractNotFound] {}", ex.getMessage());
        ErrorCode code = ErrorCode.LEASE_CONTRACT_NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), ex.getMessage()));
    }
}
