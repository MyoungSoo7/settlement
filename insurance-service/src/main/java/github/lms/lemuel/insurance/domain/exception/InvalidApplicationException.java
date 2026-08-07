package github.lms.lemuel.insurance.domain.exception;

/**
 * 청약 입력 검증 실패 — 0 이하 금액·반려 사유 누락 등.
 *
 * <p>{@link InvalidCommissionScheduleException} 과 동형 — 메시지 전용 생성자만 둔다.
 */
public class InvalidApplicationException extends IllegalArgumentException {

    public InvalidApplicationException(String message) {
        super(message);
    }
}
