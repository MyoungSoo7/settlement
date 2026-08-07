package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.CommissionStatus;

/**
 * 수수료 회차(CommissionSchedule) 상태머신이 허용하지 않는 전이 시도.
 *
 * <p>허용 전이 4개(SCHEDULED→PAID, SCHEDULED→CANCELLED, PAID→CLAWBACK_PENDING,
 * CLAWBACK_PENDING→CLAWED_BACK) 외의 모든 전이 시도 시 이 예외를 던진다. 조용히 무시 금지.
 *
 * <p>{@link InvalidPolicyTransitionException} 과 동형 — 도메인은 프레임워크·공통모듈
 * 예외 계층에 묶이지 않는 순수 {@link RuntimeException} 을 던진다.
 */
public class InvalidCommissionTransitionException extends RuntimeException {

    public InvalidCommissionTransitionException(String message) {
        super(message);
    }

    public InvalidCommissionTransitionException(CommissionStatus from, CommissionStatus to) {
        super("허용되지 않는 수수료 회차 상태 전이: " + from + " → " + to);
    }
}
