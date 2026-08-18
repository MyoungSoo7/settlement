package github.lms.lemuel.giftcard.domain.exception;

import java.math.BigDecimal;

/**
 * 기프트카드 금액이 도메인 규약을 어겼을 때 — 양수 + 1원 단위 정수.
 */
public class InvalidGiftCardAmountException extends RuntimeException {

    private final String operation;
    private final BigDecimal amount;

    public InvalidGiftCardAmountException(String message, String operation, BigDecimal amount) {
        super(message);
        this.operation = operation;
        this.amount = amount;
    }

    public String getOperation() { return operation; }
    public BigDecimal getAmount() { return amount; }
}
