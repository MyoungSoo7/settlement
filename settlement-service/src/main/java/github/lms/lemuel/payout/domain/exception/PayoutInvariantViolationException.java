package github.lms.lemuel.payout.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 출금 도메인 불변식 위반 — 금액/필수값 규칙을 어겼다
 * (amount 양수, firmBankingTransactionId·실패사유·취소사유·계좌 필드 필수).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class PayoutInvariantViolationException extends PayoutDomainException {

    public PayoutInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
