package github.lms.lemuel.settlement.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 정산(settlement) 도메인 예외의 공통 베이스 — 이 바운디드 컨텍스트가 소유한다.
 *
 * <p>{@link BusinessException} 을 상속하므로 {@code ErrorCode}(400 계열)를 통해 공통
 * {@code GlobalExceptionHandler} 가 기존 {@code IllegalArgument/IllegalState} 와 동일한 상태/응답으로
 * 매핑한다(웹 계약 불변). 순수 java 로 유지되어 헥사고날 도메인 순수성을 지킨다.
 *
 * <p>조회 실패는 기존 {@link SettlementNotFoundException}/{@link PaymentNotFoundException}(404)이 담당한다.
 */
public abstract class SettlementDomainException extends BusinessException {

    protected SettlementDomainException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
