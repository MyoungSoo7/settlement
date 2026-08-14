package github.lms.lemuel.closing.domain.exception;

import github.lms.lemuel.closing.domain.ClosingRunStatus;
import github.lms.lemuel.common.exception.ErrorCode;

/** 허용되지 않은 마감 run 상태 전이 시도 — 종결(COMPLETED/FAILED) run 재전이 등 (400). */
public class InvalidClosingRunStateException extends ClosingDomainException {

    public InvalidClosingRunStateException(ClosingRunStatus from, ClosingRunStatus to) {
        super(ErrorCode.INVALID_STATE, "마감 run 상태 전이 불가: " + from + " → " + to);
    }
}
