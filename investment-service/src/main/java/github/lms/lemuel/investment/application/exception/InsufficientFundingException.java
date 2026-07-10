package github.lms.lemuel.investment.application.exception;

/** 가용 재원(available) 부족으로 주문/집행 불가 → HTTP 422. */
public class InsufficientFundingException extends RuntimeException {
    public InsufficientFundingException(String message) {
        super(message);
    }
}
