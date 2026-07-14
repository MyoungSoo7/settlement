package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * investment 도메인 예외 → HTTP 상태 매핑. shared-common 의 공통 GlobalExceptionHandler(LOWEST_PRECEDENCE,
 * IllegalArgument/State→400·검증실패→400·기타→500) 보다 먼저(HIGHEST_PRECEDENCE) 잡아
 * NotFound→404, 부적격·재원부족→422 를 확정한다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvestmentExceptionHandler {

    @ExceptionHandler(InvestmentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(InvestmentNotFoundException e) {
        return body(ErrorCode.INVESTMENT_NOT_FOUND.status(), e.getMessage());
    }

    @ExceptionHandler(NotInvestableException.class)
    public ResponseEntity<Map<String, Object>> notInvestable(NotInvestableException e) {
        return body(ErrorCode.NOT_INVESTABLE.status(), e.getMessage());
    }

    @ExceptionHandler(InsufficientFundingException.class)
    public ResponseEntity<Map<String, Object>> insufficientFunding(InsufficientFundingException e) {
        return body(ErrorCode.INSUFFICIENT_FUNDING.status(), e.getMessage());
    }

    /** 소유권 위반(타 셀러 리소스 접근) → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "message", message == null ? "" : message));
    }
}
