package github.lms.lemuel.insurance.domain.exception;

/**
 * 수수료 스케줄 생성 실패 예외.
 *
 * <p>입력 검증 실패 (null, 음수, 0원 등).
 */
public class InvalidCommissionScheduleException extends IllegalArgumentException {

    public InvalidCommissionScheduleException(String message) {
        super(message);
    }

    public InvalidCommissionScheduleException(String message, Throwable cause) {
        super(message, cause);
    }
}
