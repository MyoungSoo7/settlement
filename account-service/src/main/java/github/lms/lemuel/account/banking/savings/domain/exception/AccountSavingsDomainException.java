package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 적금(banking.savings) 서브도메인 예외의 공통 베이스.
 *
 * <p>계정계 도메인 예외 베이스({@link AccountDomainException})를 그대로 상속해 공통
 * {@code GlobalExceptionHandler} 의 ErrorCode → HTTP 매핑을 재사용한다. 서브도메인이 자기 베이스를
 * 하나 두는 이유는 "적금이 던진 것"을 호출부가 한 타입으로 잡을 수 있게 하기 위함이다.
 *
 * <p><b>절대 {@code IllegalArgumentException} 을 던지지 않는다</b> — 그건 프레임워크가 만들어내는
 * 예외와 구분되지 않아 400 폴백에 섞이고, 어떤 불변식이 깨졌는지 코드로 식별할 수 없다.
 */
public abstract class AccountSavingsDomainException extends AccountDomainException {

    protected AccountSavingsDomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
