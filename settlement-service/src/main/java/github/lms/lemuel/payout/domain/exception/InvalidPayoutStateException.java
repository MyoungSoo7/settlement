package github.lms.lemuel.payout.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.payout.domain.PayoutStatus;

/**
 * 출금 상태머신 위반 — 허용되지 않은 전이({@code from → to})를 시도했다
 * (startSending/markCompleted/markFailed/retry/cancel).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이의 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidPayoutStateException extends PayoutDomainException {

    private final transient PayoutStatus from;
    private final transient PayoutStatus to;

    public InvalidPayoutStateException(PayoutStatus from, PayoutStatus to) {
        super(ErrorCode.INVALID_STATE, "출금 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    public PayoutStatus getFrom() {
        return from;
    }

    public PayoutStatus getTo() {
        return to;
    }
}
