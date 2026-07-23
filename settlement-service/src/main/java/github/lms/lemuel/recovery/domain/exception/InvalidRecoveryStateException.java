package github.lms.lemuel.recovery.domain.exception;

import github.lms.lemuel.recovery.domain.RecoveryStatus;

/** 채권 상태머신 위반 (CLOSED·MANUAL_REQUIRED 이후의 상계·전이 시도). */
public class InvalidRecoveryStateException extends RuntimeException {

    public InvalidRecoveryStateException(RecoveryStatus current, String attempted) {
        super("recovery 상태 " + current + " 에서 " + attempted + " 불가");
    }
}
