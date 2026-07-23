package github.lms.lemuel.recovery.domain.exception;

/** 채권·상계 불변식 위반 (원금·배분 금액·필수 식별자) — 금융 도메인 타입 예외. */
public class RecoveryInvariantViolationException extends RuntimeException {

    public RecoveryInvariantViolationException(String message) {
        super(message);
    }
}
