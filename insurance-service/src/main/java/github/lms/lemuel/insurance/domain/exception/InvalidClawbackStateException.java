package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.PolicyStatus;

/**
 * 환수(clawback) 불가 상태에서 계산을 시도했을 때 발생.
 *
 * <p>D6: 환수 대상이 아닌 상태(ACTIVE, LAPSED)에서 ClawbackCalculator 호출 시 던짐.
 */
public class InvalidClawbackStateException extends RuntimeException {

    private final PolicyStatus attemptedStatus;

    public InvalidClawbackStateException(String message, PolicyStatus attemptedStatus) {
        super(message);
        this.attemptedStatus = attemptedStatus;
    }

    public PolicyStatus getAttemptedStatus() {
        return attemptedStatus;
    }
}
