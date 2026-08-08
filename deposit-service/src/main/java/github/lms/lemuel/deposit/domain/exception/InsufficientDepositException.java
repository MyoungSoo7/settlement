package github.lms.lemuel.deposit.domain.exception;

/**
 * 예치금 잔고 부족 예외 (도메인 계층 — Spring 의존 0).
 *
 * <p>available < 요청액 또는 locked < 해제·캡처 요청액일 때 도메인이 던진다.
 * 이 예외는 비즈니스 정상 결과이므로 Kafka 컨슈머는 catch 후 shortfall 레코드를
 * 영속화하고 정상 ack 한다 — 재시도·DLQ 대상이 아니다.
 */
public class InsufficientDepositException extends RuntimeException {

    private final String sellerId;
    private final String operation;

    public InsufficientDepositException(String message, String sellerId, String operation) {
        super(message);
        this.sellerId = sellerId;
        this.operation = operation;
    }

    public String getSellerId() { return sellerId; }
    public String getOperation() { return operation; }
}
