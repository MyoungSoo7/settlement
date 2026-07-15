package github.lms.lemuel.account.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 계정계(account) 도메인 예외의 공통 베이스 — 이 바운디드 컨텍스트가 소유한다.
 *
 * <p>{@link BusinessException} 을 상속하므로 {@code ErrorCode} 를 통해 공통
 * {@code GlobalExceptionHandler} 가 기존과 동일한 HTTP 상태/응답으로 매핑한다(웹 계약 불변).
 * 프레임워크 의존 없이 순수 java 로 유지된다(헥사고날 도메인 순수성).
 */
public abstract class AccountDomainException extends BusinessException {

    protected AccountDomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
