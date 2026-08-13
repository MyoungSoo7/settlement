package github.lms.lemuel.deposit.domain.exception;

/**
 * 상태 전이 계약 위반 (도메인 계층 — Spring 의존 0).
 *
 * <p>hold·shortfall 이 허용되지 않은 상태에서 전이를 시도했을 때 던진다(예: ACTIVE 가 아닌 hold 만료).
 * 같은 레코드는 다시 시도해도 같은 상태이므로 <b>비재시도</b> 성격이다.
 *
 * <p>{@link IllegalStateException} 을 확장하는 이유는 그 분류를 유지하기 위해서다 —
 * 공용 Kafka 에러 핸들러가 ISE 를 "즉시 DLT"로 분류한다. 타입만 좁혀 상태 위반을
 * 임의의 ISE 와 구분한다.
 */
public class InvalidDepositStateException extends IllegalStateException {

    private final String currentState;
    private final String attemptedTransition;

    public InvalidDepositStateException(String message, String currentState, String attemptedTransition) {
        super(message);
        this.currentState = currentState;
        this.attemptedTransition = attemptedTransition;
    }

    public String getCurrentState() { return currentState; }

    public String getAttemptedTransition() { return attemptedTransition; }
}
