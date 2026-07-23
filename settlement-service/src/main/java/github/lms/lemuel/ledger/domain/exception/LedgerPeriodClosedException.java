package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 마감된 원장 기간에 신규 분개를 전기하려는 시도 — 기간 잠금(period lock) 위반.
 *
 * <p>공통 {@code GlobalExceptionHandler} 가 {@link ErrorCode#LEDGER_PERIOD_CLOSED}(409)로 매핑한다.
 * 위반 기간(YM)과 전기하려던 일자를 구조적으로 보존한다.
 */
public class LedgerPeriodClosedException extends LedgerDomainException {

    private final transient YearMonth period;
    private final transient LocalDate attemptedDate;

    public LedgerPeriodClosedException(YearMonth period, LocalDate attemptedDate) {
        super(ErrorCode.LEDGER_PERIOD_CLOSED,
                "마감된 원장 기간 " + period + " 에는 신규 분개를 전기할 수 없습니다 (일자=" + attemptedDate + ")");
        this.period = period;
        this.attemptedDate = attemptedDate;
    }

    public YearMonth getPeriod() {
        return period;
    }

    public LocalDate getAttemptedDate() {
        return attemptedDate;
    }
}
