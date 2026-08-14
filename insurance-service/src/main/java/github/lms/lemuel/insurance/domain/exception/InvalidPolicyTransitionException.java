package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.PolicyStatus;

/**
 * 보험 계약(Policy) 상태머신이 허용하지 않는 전이 시도.
 *
 * <p>D7 — 허용 전이 7개 외의 모든 전이 시도 시 이 예외를 던진다. 조용히 무시 금지.
 *
 * <p>도메인은 프레임워크·공통모듈 예외 계층에 묶이지 않는 순수 {@link RuntimeException} 을 던진다
 * ({@code card-service} 의 {@code InvalidCardTransitionException} 과 동형).
 */
public class InvalidPolicyTransitionException extends RuntimeException {

    public InvalidPolicyTransitionException(String message) {
        super(message);
    }

    public InvalidPolicyTransitionException(PolicyStatus from, PolicyStatus to) {
        super("허용되지 않는 계약 상태 전이: " + from + " → " + to);
    }
}
