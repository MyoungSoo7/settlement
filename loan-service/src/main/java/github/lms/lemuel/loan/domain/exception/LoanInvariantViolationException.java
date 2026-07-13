package github.lms.lemuel.loan.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 대출 도메인 불변식 위반 — 신청/상환/전표/투영의 입력·값 규칙을 어겼다
 * (예: 종목코드 형식, 원금·수수료·상환액 부적격, 신용점수 범위, 전표 금액 양수, 필수값 누락).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class LoanInvariantViolationException extends LoanDomainException {

    public LoanInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
