package github.lms.lemuel.closing.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 정보계 월마감 도메인 예외 최상위 — 마감 규칙 위반은 전부 이 계열의 타입 예외로 던진다.
 *
 * <p>{@link BusinessException} 상속으로 공통 {@code GlobalExceptionHandler} 가 {@code ErrorCode} 의
 * HTTP 상태로 매핑한다(ledger 도메인 예외와 동일 패턴).
 */
public abstract class ClosingDomainException extends BusinessException {

    protected ClosingDomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    protected ClosingDomainException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
