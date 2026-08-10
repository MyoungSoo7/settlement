package github.lms.lemuel.insurance.domain.exception;

import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;

/**
 * 일반지급 상태머신(D-G4) 비허용 전이 — REQUESTED → PAID 외의 모든 전이 시도.
 */
public class InvalidGeneralPayoutTransitionException extends RuntimeException {

    public InvalidGeneralPayoutTransitionException(GeneralPayoutStatus from, GeneralPayoutStatus to) {
        super("허용되지 않는 일반지급 상태 전이: " + from + " → " + to);
    }
}
