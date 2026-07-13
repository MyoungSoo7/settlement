package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 대출(loan) 도메인 불변식/상태전이 예외의 공통 베이스 — 이 바운디드 컨텍스트가 소유한다.
 *
 * <p>{@link BusinessException} 을 상속하므로 {@code ErrorCode}(400 계열) 를 통해 공통
 * {@code GlobalExceptionHandler} 가 기존 {@code IllegalArgument/IllegalState} 와 동일한 상태/응답으로
 * 매핑한다(웹 계약 불변). 신용 거절(422)은 별도 {@link github.lms.lemuel.loan.domain.CorporateLoanRejectedException}
 * 가 담당하므로 여기서는 다루지 않는다. 순수 java 로 유지된다(헥사고날 도메인 순수성).
 */
public abstract class LoanDomainException extends BusinessException {

    protected LoanDomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
