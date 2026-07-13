package github.lms.lemuel.chargeback.domain.exception;

import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 카드사 분쟁 상태머신 위반 — 허용되지 않은 전이·연산을 시도했다
 * (OPEN → ACCEPTED|REJECTED 만 허용, 종료 상태에서 settlementId 변경 불가).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이형 위반은 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidChargebackStateException extends ChargebackDomainException {

    private final transient ChargebackStatus from;
    private final transient ChargebackStatus to;

    /** 상태 전이 위반: {@code from → to} 를 보존한다. */
    public InvalidChargebackStateException(ChargebackStatus from, ChargebackStatus to) {
        super(ErrorCode.INVALID_STATE, "Chargeback 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    /** 현재 상태에서 허용되지 않은 연산(전이 대상이 없는 불변식). */
    public InvalidChargebackStateException(ChargebackStatus from, String operation) {
        super(ErrorCode.INVALID_STATE, operation + " (현재 상태=" + from + ")");
        this.from = from;
        this.to = null;
    }

    public ChargebackStatus getFrom() {
        return from;
    }

    public ChargebackStatus getTo() {
        return to;
    }
}
