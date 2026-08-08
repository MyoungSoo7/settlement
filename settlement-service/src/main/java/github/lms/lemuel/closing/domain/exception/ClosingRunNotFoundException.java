package github.lms.lemuel.closing.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.time.YearMonth;

/** 조회한 기간의 마감 run 이 존재하지 않음 — 아직 한 번도 마감이 실행되지 않은 월 (404). */
public class ClosingRunNotFoundException extends ClosingDomainException {

    public ClosingRunNotFoundException(YearMonth period) {
        super(ErrorCode.MONTHLY_CLOSING_NOT_FOUND, "월마감 run 없음: " + period);
    }
}
