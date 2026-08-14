package github.lms.lemuel.closing.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.time.YearMonth;

/** 월마감 실행 실패 — 집계·적재 중 오류. FAILED run 이 기록된 뒤 이 예외로 전파된다 (500). */
public class MonthlyClosingFailedException extends ClosingDomainException {

    public MonthlyClosingFailedException(YearMonth period, Throwable cause) {
        super(ErrorCode.MONTHLY_CLOSING_FAILED, "월마감 실행 실패: " + period, cause);
    }
}
