package github.lms.lemuel.loan.domain.exception;

/**
 * 담보/개인신용 대출을 찾을 수 없음 — HTTP 404 로 매핑된다
 * ({@link CorporateLoanNotFoundException} 동형).
 */
public class SecuredLoanNotFoundException extends RuntimeException {

    public SecuredLoanNotFoundException(String message) {
        super(message);
    }
}
