package github.lms.lemuel.insurance.domain.exception;

/**
 * 월 수수료 마감 스냅샷 생성 실패 — 음수 합계·음수 회차 수 등 입력 검증 위반.
 *
 * <p>{@link InvalidCommissionScheduleException} 과 동형 — 메시지 전용 생성자만 둔다.
 */
public class InvalidCommissionClosingException extends IllegalArgumentException {

    public InvalidCommissionClosingException(String message) {
        super(message);
    }
}
