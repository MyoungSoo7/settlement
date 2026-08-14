package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.exception.ErrorResponse;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotFoundException;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotMatchedException;
import github.lms.lemuel.deposit.domain.exception.DepositProofOcrUnavailableException;
import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * deposit 도메인 예외 → HTTP 번역. shared-common {@code GlobalExceptionHandler}(LOWEST_PRECEDENCE)
 * 보다 먼저 잡아야 도메인 예외가 500 으로 새지 않으므로 HIGHEST_PRECEDENCE 다(card 선례).
 */
@Hidden
@RestControllerAdvice(basePackages = "github.lms.lemuel.deposit")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DepositExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DepositExceptionHandler.class);

    /**
     * 잔고 부족은 서버 오류가 아니라 정상적인 거절이다. 422 로 내리고 도메인이 남긴 사유
     * (어느 잔고가 얼마 모자랐는지)를 그대로 전달한다.
     */
    @ExceptionHandler(InsufficientDepositException.class)
    public ResponseEntity<ErrorResponse> handleInsufficient(InsufficientDepositException e) {
        log.warn("[InsufficientDeposit] sellerId={} op={} {}", e.getSellerId(), e.getOperation(), e.getMessage());
        return toResponse(ErrorCode.INSUFFICIENT_DEPOSIT, e.getMessage());
    }

    /**
     * {@code uq_deposit_entries_natural} 충돌 — 같은 referenceId 로 두 번 기표하려 한 경우다.
     * 트랜잭션이 통째로 롤백되므로 잔고는 움직이지 않았고, 호출자에게는 "이미 처리됨"(409)이 정답이다.
     * 500 으로 내리면 클라이언트가 재시도해 같은 충돌을 반복한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DataIntegrityViolationException e) {
        log.warn("[DuplicateDepositEntry] {}", e.getMostSpecificCause().getMessage());
        return toResponse(ErrorCode.DUPLICATE_DEPOSIT_ENTRY, null);
    }

    /**
     * 계좌 없는 셀러에 대한 출금·선점·상계 시도. 유스케이스가 generic
     * {@link IllegalStateException} 으로 던지므로 여기서 404 로 번역한다 — 번역하지 않으면
     * "없는 계좌"가 500 으로 나가 운영자가 장애로 오인한다.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleMissingAccount(IllegalStateException e) {
        log.warn("[DepositAccountMissing] {}", e.getMessage());
        return toResponse(ErrorCode.DEPOSIT_ACCOUNT_NOT_FOUND, e.getMessage());
    }

    /**
     * 본문 파싱 실패(알 수 없는 holderType enum, 깨진 JSON 등). shared-common
     * {@code GlobalExceptionHandler} 에는 이 타입의 핸들러가 없어 catch-all 을 타고 500 이 나간다 —
     * 보낸 쪽 잘못을 서버 장애로 보고하지 않도록 400 으로 명시 번역한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("[DepositUnreadableBody] {}", e.getMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, "요청 본문을 해석할 수 없습니다.");
    }

    /** 금액이 0·음수 등 도메인 인수 검증 실패 — 요청이 잘못된 것이므로 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidArgument(IllegalArgumentException e) {
        log.warn("[DepositInvalidArgument] {}", e.getMessage());
        return toResponse(ErrorCode.INVALID_ARGUMENT, e.getMessage());
    }

    // ── 예치금 증빙 (ADR 0036) ──

    /** 증빙 미존재 — 404. ISE catch-all(계좌 404)과 코드·메시지를 구분하려 전용 타입으로 받는다. */
    @ExceptionHandler(DepositProofNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProofNotFound(DepositProofNotFoundException e) {
        log.warn("[DepositProofNotFound] {}", e.getMessage());
        return toResponse(ErrorCode.DEPOSIT_PROOF_NOT_FOUND, e.getMessage());
    }

    /** 증빙 판독 실패·미구성 — 무폴백 503 (재시도 가능한 일시 장애). */
    @ExceptionHandler(DepositProofOcrUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleProofOcrUnavailable(DepositProofOcrUnavailableException e) {
        log.warn("[DepositProofOcrUnavailable] {}", e.getMessage());
        return toResponse(ErrorCode.DEPOSIT_PROOF_OCR_UNAVAILABLE, e.getMessage());
    }

    /** 증빙 대사 미통과 기표 — 요청 형식이 아니라 "지금은 기표 불가"라서 422 (잔고 부족과 같은 결). */
    @ExceptionHandler(DepositProofNotMatchedException.class)
    public ResponseEntity<ErrorResponse> handleProofNotMatched(DepositProofNotMatchedException e) {
        log.warn("[DepositProofNotMatched] {}", e.getMessage());
        return toResponse(ErrorCode.DEPOSIT_PROOF_NOT_MATCHED, e.getMessage());
    }

    /**
     * 컨트롤러가 JWT 주체에서 sellerId 를 못 뽑았을 때. 시큐리티 필터가 꺼진 테스트 경로에서도
     * 500 이 아니라 403 이 되도록 명시 번역한다(card/loan 선례).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        log.warn("[AccessDenied] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", e.getMessage()));
    }

    private static ResponseEntity<ErrorResponse> toResponse(ErrorCode code, String message) {
        String body = (message == null || message.isBlank()) ? code.defaultMessage() : message;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.status(), code.code(), body));
    }
}
