package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.ledger.domain.LedgerPeriodStatus;

/**
 * 원장 기간 상태머신 위반 — 허용되지 않은 전이({@code from → to})를 시도했다(예: 이미 CLOSED 인 기간의 재마감).
 *
 * <p>공통 핸들러가 {@link ErrorCode#INVALID_STATE}(400)로 매핑한다. 재마감 멱등 처리는
 * 유스케이스 계층이 상태를 먼저 확인해 no-op 으로 흡수하므로, 이 예외는 도메인 불변식의 최종 방어선이다.
 */
public class InvalidLedgerPeriodStateException extends LedgerDomainException {

    private final transient LedgerPeriodStatus from;
    private final transient LedgerPeriodStatus to;

    public InvalidLedgerPeriodStateException(LedgerPeriodStatus from, LedgerPeriodStatus to) {
        super(ErrorCode.INVALID_STATE, "원장 기간 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    public LedgerPeriodStatus getFrom() {
        return from;
    }

    public LedgerPeriodStatus getTo() {
        return to;
    }
}
