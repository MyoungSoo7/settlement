package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.ApplicationStatus;

/**
 * 청약(InsuranceApplication) 상태머신이 허용하지 않는 전이 시도.
 *
 * <p>허용 전이 3개(SUBMITTED→UNDER_REVIEW, UNDER_REVIEW→APPROVED, UNDER_REVIEW→REJECTED)
 * 외의 모든 전이 시도 시 이 예외를 던진다. 조용히 무시 금지.
 */
public class InvalidApplicationTransitionException extends RuntimeException {

    public InvalidApplicationTransitionException(String message) {
        super(message);
    }

    public InvalidApplicationTransitionException(ApplicationStatus from, ApplicationStatus to) {
        super("허용되지 않는 청약 상태 전이: " + from + " → " + to);
    }
}
