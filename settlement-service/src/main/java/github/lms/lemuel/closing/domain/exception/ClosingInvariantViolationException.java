package github.lms.lemuel.closing.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/** 월마감 불변식 위반 — 필수값 누락, 음수 금액/건수, 완결되지 않은 월 마감 시도 등 (400). */
public class ClosingInvariantViolationException extends ClosingDomainException {

    public ClosingInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
