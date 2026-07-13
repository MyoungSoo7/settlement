package github.lms.lemuel.settlement.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.settlement.domain.SettlementStatus;

/**
 * 정산 상태머신/불변 상태 위반 — 현재 상태에서 허용되지 않은 전이·연산을 시도했다
 * (예: PROCESSING 이 아닌데 complete, DONE 정산의 금액 변경, netAmount 미계산 상태의 holdback).
 *
 * <p>기존 {@code IllegalStateException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 전이형 위반은 출발/목표 상태를 {@link #getFrom()}·{@link #getTo()} 로 구조적으로 보존한다.
 */
public class InvalidSettlementStateException extends SettlementDomainException {

    private final transient SettlementStatus from;
    private final transient SettlementStatus to;

    /** 상태 전이 위반: {@code from → to} 를 그대로 보존한다. */
    public InvalidSettlementStateException(SettlementStatus from, SettlementStatus to) {
        super(ErrorCode.INVALID_STATE, "정산 상태 전이 불가: " + from + " → " + to);
        this.from = from;
        this.to = to;
    }

    /** 현재 상태에서 허용되지 않은 연산(전이 대상이 특정되지 않는 불변식). */
    public InvalidSettlementStateException(SettlementStatus from, String operation) {
        super(ErrorCode.INVALID_STATE, operation + " (현재 상태=" + from + ")");
        this.from = from;
        this.to = null;
    }

    /** 상태와 무관한 사전조건 위반. */
    public InvalidSettlementStateException(String message) {
        super(ErrorCode.INVALID_STATE, message);
        this.from = null;
        this.to = null;
    }

    public SettlementStatus getFrom() {
        return from;
    }

    public SettlementStatus getTo() {
        return to;
    }
}
