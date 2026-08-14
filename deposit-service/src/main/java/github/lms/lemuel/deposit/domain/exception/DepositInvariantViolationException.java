package github.lms.lemuel.deposit.domain.exception;

/**
 * 예치금 불변식 위반 (도메인 계층 — Spring 의존 0).
 *
 * <p>도달해서는 안 되는 상태를 도메인이 스스로 발견했을 때 던진다 —
 * {@code total = available + locked} 붕괴, 세 잔고 필드의 음수화, write-once 식별자 재할당 등.
 *
 * <p>{@link InvalidDepositAmountException}(입력이 잘못됨)과 다르다. 이쪽은 <b>입력이 아니라
 * 우리 코드나 데이터가 잘못됐다</b>는 신호이므로, 잡아서 흘려보내지 말고 실패로 드러내야 한다.
 *
 * <p>{@link IllegalStateException} 을 확장해 공용 Kafka 에러 핸들러의 "즉시 DLT" 분류를 유지한다 —
 * 불변식이 깨진 상태에서 재시도는 같은 결과를 반복할 뿐이다.
 */
public class DepositInvariantViolationException extends IllegalStateException {

    public DepositInvariantViolationException(String message) {
        super(message);
    }
}
