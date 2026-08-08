package github.lms.lemuel.closing.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.time.YearMonth;

/**
 * 원장 마감(ledger_periods CLOSED)된 기간의 정보계 재마감 시도 — 확정 수치 변조 방지 (409).
 *
 * <p>원장이 마감된 월은 회계적으로 확정된 기간이다. 그 위의 정보계 마트를 다시 적재하면
 * 이미 보고된 수치가 바뀔 수 있으므로, COMPLETED 마트가 존재하는 한 재마감을 거부한다.
 */
public class MonthlyClosingLockedException extends ClosingDomainException {

    public MonthlyClosingLockedException(YearMonth period) {
        super(ErrorCode.MONTHLY_CLOSING_LOCKED, "원장 마감된 기간의 정보계 재마감 불가: " + period);
    }
}
