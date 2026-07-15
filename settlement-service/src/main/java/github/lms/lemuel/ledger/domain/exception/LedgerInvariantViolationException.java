package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 원장 항목 불변식 위반 — 분개 작성 시 필수값/금액 규칙을 어겼다
 * (referenceId·referenceType·entryType·계정·settlementDate 필수, amount 양수).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 차/대 동일 계정(구성 불균형)은 하위 {@link UnbalancedLedgerEntryException} 로 세분한다.
 */
public class LedgerInvariantViolationException extends LedgerDomainException {

    public LedgerInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
