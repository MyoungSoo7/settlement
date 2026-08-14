package github.lms.lemuel.deposit.domain.exception;

/**
 * 예치금 증빙 입력 검증 위반 — 필수값 누락·범위 밖 값·빈 파일·불가 전이.
 *
 * <p>{@link IllegalArgumentException} 상속 — {@code DepositExceptionHandler} 의 IAE 매핑(400)을 승계하고,
 * Kafka 컨슈머 오류 분류(재시도 무익 → 즉시 DLT)도 기존 IAE 계열과 같게 유지한다.
 */
public class InvalidDepositProofException extends IllegalArgumentException {

    public InvalidDepositProofException(String message) {
        super(message);
    }
}
