package github.lms.lemuel.loan.domain.exception;

/**
 * 기업대출/재무자료 조회 실패 — 요청한 대출 건이나 상장사 재무자료가 존재하지 않는 경우.
 * HTTP 404(Not Found)로 매핑된다(CorporateLoanExceptionHandler).
 *
 * <p>도메인 순수성: JDK RuntimeException 만 상속하며 Spring/HTTP 에 의존하지 않는다.
 */
public class CorporateLoanNotFoundException extends RuntimeException {

    public CorporateLoanNotFoundException(String message) {
        super(message);
    }
}
