package github.lms.lemuel.investment.application.exception;

/** 투자 부적격(총점 60 미만) 종목에 대한 주문 시도 → HTTP 422. */
public class NotInvestableException extends RuntimeException {
    public NotInvestableException(String message) {
        super(message);
    }
}
