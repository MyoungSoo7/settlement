package github.lms.lemuel.card.adapter.in.web;

import github.lms.lemuel.card.application.port.out.FundingUnavailableException;
import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import github.lms.lemuel.card.domain.exception.SubLimitExceededException;
import github.lms.lemuel.common.exception.ErrorCode;
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
 * card 도메인 예외 → HTTP 번역. shared-common {@code GlobalExceptionHandler}(LOWEST_PRECEDENCE)
 * 보다 먼저 잡아야 도메인 예외가 500 으로 새지 않으므로 HIGHEST_PRECEDENCE 다.
 *
 * <p>card 도메인 예외는 의도적으로 {@code BusinessException} 을 상속하지 않는다 —
 * ErrorCode 매핑은 도메인이 아니라 바깥 계층의 책임이라는 결정이고, 그 매핑 지점이 여기다.
 */
@Hidden
@RestControllerAdvice(basePackages = "github.lms.lemuel.card")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CardExceptionHandler.class);

    @ExceptionHandler(SubLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleSubLimit(SubLimitExceededException e) {
        log.warn("[CardSubLimitExceeded] {}", e.getMessage());
        return toResponse(ErrorCode.CARD_SUB_LIMIT_EXCEEDED, e.getMessage());
    }

    @ExceptionHandler(InvalidCardTransitionException.class)
    public ResponseEntity<ErrorResponse> handleTransition(InvalidCardTransitionException e) {
        log.warn("[InvalidCardTransition] {}", e.getMessage());
        return toResponse(ErrorCode.INVALID_STATE, e.getMessage());
    }

    /**
     * 유스케이스가 이미 {@code BusinessException(CARD_FUNDING_UNAVAILABLE)} 로 번역하므로 여기까지
     * 오는 경로는 원칙적으로 없다. 그럼에도 남겨 두는 이유는, 번역을 잊은 새 경로가 생겼을 때
     * 500 + 스택트레이스가 아니라 503 으로 나가게 하기 위해서다. <b>폴백은 여전히 없다</b> —
     * 재원을 모른 채 추정 한도를 주지 않는다.
     */
    @ExceptionHandler(FundingUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleFunding(FundingUnavailableException e) {
        log.error("[CardFundingUnavailable] {}", e.getMessage());
        return toResponse(ErrorCode.CARD_FUNDING_UNAVAILABLE, null);
    }

    /**
     * 컨트롤러가 JWT 주체에서 userId 를 못 뽑았을 때. 시큐리티 필터가 꺼진 테스트 경로에서도
     * 500 이 아니라 403 이 되도록 명시 번역한다(loan-service {@code LoanSecurityExceptionHandler} 선례).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        log.warn("[AccessDenied] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", e.getMessage()));
    }

    /** 메시지가 없으면 카탈로그 기본 문구를 쓴다 — 상태·코드 문자열의 단일 출처는 ErrorCode 다. */
    private static ResponseEntity<ErrorResponse> toResponse(ErrorCode code, String message) {
        String body = (message == null || message.isBlank()) ? code.defaultMessage() : message;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), body));
    }
}
