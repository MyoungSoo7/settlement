package github.lms.lemuel.deposit.domain.exception;

import java.math.BigDecimal;

/**
 * 금액 입력 계약 위반 (도메인 계층 — Spring 의존 0).
 *
 * <p>null 이거나 양수가 아닌 금액이 도메인에 들어왔을 때 던진다. 재시도해도 같은 값이 다시
 * 거부되므로 <b>비재시도</b> 성격이다.
 *
 * <p>{@link IllegalArgumentException} 을 확장하는 이유는 그 분류를 유지하기 위해서다 —
 * 공용 Kafka 에러 핸들러({@code KafkaConsumerErrorHandlingConfig})가 IAE 를 "즉시 DLT"로
 * 분류하므로, 소비 경로가 붙어도 무의미한 재시도 3회를 돌지 않는다. 타입만 좁혀
 * 로그·핸들링에서 임의의 IAE 와 구분한다.
 */
public class InvalidDepositAmountException extends IllegalArgumentException {

    private final String operation;
    private final BigDecimal amount;

    public InvalidDepositAmountException(String message, String operation, BigDecimal amount) {
        super(message);
        this.operation = operation;
        this.amount = amount;
    }

    public String getOperation() { return operation; }

    public BigDecimal getAmount() { return amount; }
}
