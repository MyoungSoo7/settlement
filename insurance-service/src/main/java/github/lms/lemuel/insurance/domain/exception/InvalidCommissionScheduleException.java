package github.lms.lemuel.insurance.domain.exception;

/**
 * 수수료 스케줄 생성 실패 예외.
 *
 * <p>입력 검증 실패 (null, 0 이하, 통화 최소단위 미만 등).
 *
 * <p>{@link InvalidPolicyTransitionException} 과 동일하게 메시지 전용 생성자만 둔다 —
 * 이 예외는 원인 예외를 감싸는 경로가 없다.
 */
public class InvalidCommissionScheduleException extends IllegalArgumentException {

    public InvalidCommissionScheduleException(String message) {
        super(message);
    }
}
