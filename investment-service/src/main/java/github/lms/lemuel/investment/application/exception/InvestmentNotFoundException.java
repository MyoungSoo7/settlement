package github.lms.lemuel.investment.application.exception;

/** 재무제표/주문 등 대상 리소스를 찾을 수 없음 → HTTP 404. */
public class InvestmentNotFoundException extends RuntimeException {
    public InvestmentNotFoundException(String message) {
        super(message);
    }
}
